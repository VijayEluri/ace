/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ace.client.repository.stateful.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.ace.client.repository.RepositoryAdmin;
import org.apache.ace.client.repository.RepositoryObject;
import org.apache.ace.client.repository.RepositoryUtil;
import org.apache.ace.client.repository.SessionFactory;
import org.apache.ace.client.repository.helper.bundle.BundleHelper;
import org.apache.ace.client.repository.object.ArtifactObject;
import org.apache.ace.client.repository.object.DeploymentArtifact;
import org.apache.ace.client.repository.object.DeploymentVersionObject;
import org.apache.ace.client.repository.object.TargetObject;
import org.apache.ace.client.repository.object.FeatureObject;
import org.apache.ace.client.repository.object.DistributionObject;
import org.apache.ace.client.repository.repository.ArtifactRepository;
import org.apache.ace.client.repository.repository.DeploymentVersionRepository;
import org.apache.ace.client.repository.repository.TargetRepository;
import org.apache.ace.client.repository.stateful.StatefulTargetObject;
import org.apache.ace.client.repository.stateful.StatefulTargetRepository;
import org.apache.ace.log.LogDescriptor;
import org.apache.ace.log.LogEvent;
import org.apache.ace.server.log.store.LogStore;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.Version;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.event.EventHandler;
import org.osgi.service.log.LogService;

/**
 * Implements the StatefulTargetRepository. If an <code>AuditLogStore</code> is present,
 * it will be used; it is assumed that the auditlog store is up to date.
 */
public class StatefulTargetRepositoryImpl implements StatefulTargetRepository, EventHandler {
    private BundleContext m_context; /* Injected by dependency manager */
    private ArtifactRepository m_artifactRepository; /* Injected by dependency manager */
    private TargetRepository m_targetRepository; /* Injected by dependency manager */
    private DeploymentVersionRepository m_deploymentRepository; /* Injected by dependency manager */
    private LogStore m_auditLogStore; /* Injected by dependency manager */
    private EventAdmin m_eventAdmin; /* Injected by dependency manager */
    private LogService m_log; /* Injected by dependency manager */
    private BundleHelper m_bundleHelper; /* Injected by dependency manager */
    // TODO: Make the concurrencyLevel of this concurrent hashmap settable?
    private Map<String, StatefulTargetObjectImpl> m_repository = new ConcurrentHashMap<String, StatefulTargetObjectImpl>();
    private Map<String, StatefulTargetObjectImpl> m_index = new ConcurrentHashMap<String, StatefulTargetObjectImpl>();
    private final String m_sessionID;

    public StatefulTargetRepositoryImpl(String sessionID) {
        m_sessionID = sessionID;
    }

    public StatefulTargetObject create(Map<String, String> attributes, Map<String, String> tags)
        throws IllegalArgumentException {
        throw new UnsupportedOperationException("Creating StatefulTargetObjects is not supported.");
    }

    public List<StatefulTargetObject> get() {
        synchronized (m_repository) {
            List<StatefulTargetObject> result = new ArrayList<StatefulTargetObject>();
            for (StatefulTargetObjectImpl sgoi : m_repository.values()) {
                result.add(sgoi);
            }
            return result;
        }
    }

    public List<StatefulTargetObject> get(Filter filter) {
        synchronized (m_repository) {
            List<StatefulTargetObject> result = new ArrayList<StatefulTargetObject>();
            for (StatefulTargetObject entry : m_repository.values()) {
                if (filter.match(entry.getDictionary())) {
                    result.add(entry);
                }
            }
            return result;
        }
    }

    public StatefulTargetObject get(String definition) {
        return m_index.get(definition);
    }

    public void remove(StatefulTargetObject entity) {
        synchronized (m_repository) {
            StatefulTargetObjectImpl statefulTarget = (StatefulTargetObjectImpl) entity;
            unregister(statefulTarget.getID());
            removeStateful(statefulTarget);
            // Ensure the external side sees the changes we've made...
            statefulTarget.updateTargetObject(false);
        }
    }

    public StatefulTargetObject preregister(Map<String, String> attributes, Map<String, String> tags) {
        synchronized (m_repository) {
            TargetObject to = m_targetRepository.create(attributes, tags);
            return createStateful(to.getID());
        }
    }

    public void unregister(String targetID) {
        synchronized (m_repository) {
            TargetObject to = getTargetObject(targetID);
            if (to == null) {
                throw new IllegalArgumentException(targetID + " does not represent a TargetObject.");
            }
            else {
                m_targetRepository.remove(to);
                // No need to inform the stateful representation; this will be done by the event handler.
            }
        }
    }

    public void refresh() {
        populate();
    }

    /**
     * Gets the <code>TargetObject</code> which is identified by the <code>targetID</code>.
     * 
     * @param targetID A string representing a target ID.
     * @return The <code>TargetObject</code> from the <code>TargetRepository</code> which has the given
     *         ID, or <code>null</code> if none can be found.
     */
    TargetObject getTargetObject(String targetID) {
// synchronized(m_repository) {
        try {
            List<TargetObject> targets =
                m_targetRepository.get(m_context.createFilter("(" + TargetObject.KEY_ID + "="
                    + RepositoryUtil.escapeFilterValue(targetID) + ")"));
            if ((targets != null) && (targets.size() == 1)) {
                return targets.get(0);
            }
            else {
                return null;
            }
        }
        catch (InvalidSyntaxException e) {
            // The filter syntax is illegal, probably a bad target ID.
            return null;
        }
// }
    }

    /**
     * Gets the stateful representation of the given target ID.
     * 
     * @param targetID A string representing a target ID.
     * @return The <code>StatefulTargetyObjectImpl</code> which handles the given ID,
     *         or <code>null</code> if none can be found.
     */
    StatefulTargetObjectImpl getStatefulTargetObject(String targetID) {
        synchronized (m_repository) {
            return m_repository.get(targetID);
        }
    }

    /**
     * Creates and registers a new stateful target object based on the given ID.
     * 
     * @param targetID A string representing a target ID.
     * @return The newly created and registered <code>StatefulTargetObjectImpl</code>.
     */
    private StatefulTargetObjectImpl createStateful(String targetID) {
        synchronized (m_repository) {
            StatefulTargetObjectImpl result = new StatefulTargetObjectImpl(this, targetID);
            if (add(result)) {
                return result;
            }
            else {
                throw new IllegalArgumentException("The StateTargetObject " + targetID + " already exists.");
            }
        }
    }

    /**
     * Removes the given entity from this object's repository, and notifies
     * interested parties of this.
     * 
     * @param entity The StatefulTargetObjectImpl to be removed.
     */
    void removeStateful(StatefulTargetObjectImpl entity) {
        synchronized (m_repository) {
            m_repository.remove(entity.getID());
            notifyChanged(entity, StatefulTargetObject.TOPIC_REMOVED);
        }
    }

    /**
     * Adds the given stateful object to this object's repository, and notifies
     * interested parties of this change.
     * 
     * @param stoi A <code>StatefulTargetObjectImpl</code> to be registered.
     * @return <code>true</code> when this object has been added to the repository
     *         and listeners have been notified, <code>false</code> otherwise.
     */
    boolean add(StatefulTargetObjectImpl stoi) {
        if (!m_repository.containsKey(stoi)) {
            m_repository.put(stoi.getID(), stoi);
            m_index.put(stoi.getDefinition(), stoi);
            notifyChanged(stoi, StatefulTargetObject.TOPIC_ADDED);
            return true;
        }
        return false;
    }

    private Comparator<LogEvent> m_auditEventComparator = new Comparator<LogEvent>() {
        public int compare(LogEvent left, LogEvent right) {
            if (left.getLogID() == right.getLogID()) {
                return (int) (left.getTime() - right.getTime());
            }
            else {
                return (int) (left.getLogID() - right.getLogID());
            }
        }
    };

    /**
     * Gets all auditlog events which are related to a given target ID.
     * 
     * @param targetID A string representing a target ID.
     * @return a list of <code>AuditEvent</code>s related to this target ID,
     *         ordered in the order they happened. If no events can be found, and empty list will be returned.
     */
    List<LogEvent> getAuditEvents(String targetID) {
        return getAuditEvents(getAllDescriptors(targetID));
    }

    /**
     * Gets all auditlog descriptors which are related to a given target.
     * 
     * @param targetID The target ID
     * @return A list of LogDescriptors, in no particular order.
     */
    List<LogDescriptor> getAllDescriptors(String targetID) {
        List<LogDescriptor> result = new ArrayList<LogDescriptor>();
        try {
            List<LogDescriptor> descriptors = m_auditLogStore.getDescriptors(targetID);
            if (descriptors != null) {
                result = descriptors;
            }
        }
        catch (IOException e) {
            // Too bad, but not much we can do.
            m_log.log(LogService.LOG_INFO, "Error getting descriptors from auditlog store: ", e);
        }
        return result;
    }

    /**
     * Gets all audit log events for a target is has not yet 'seen'.
     * 
     * @param all A list of all <code>LogDescriptor</code> from which to filter
     *        the new ones.
     * @param seen A list of <code>LogDescriptor</code> objects, which indicate
     *        the items the target has already processed.
     * @return All AuditLog events that are in the audit store, but are not identified
     *         by <code>oldDescriptors</code>, ordered by 'happened-before'.
     */
    List<LogEvent> getAuditEvents(List<LogDescriptor> events) {
        // Get all events from the audit log store, if possible.
        List<LogEvent> result = new ArrayList<LogEvent>();
        for (LogDescriptor l : events) {
            try {
                result.addAll(m_auditLogStore.get(l));
            }
            catch (IOException e) {
                // too bad, but not much to do.
                m_log.log(LogService.LOG_INFO, "Error getting contents from auditlog store: ", e);
            }
        }

        Collections.sort(result, m_auditEventComparator);
        return result;
    }

    List<LogDescriptor> diffLogDescriptorLists(List<LogDescriptor> all, List<LogDescriptor> seen) {
        List<LogDescriptor> descriptors = new ArrayList<LogDescriptor>();

        // Find out what events should be returned
        for (LogDescriptor s : all) {
            LogDescriptor diffs = s;
            for (LogDescriptor d : seen) {
                if ((s.getLogID() == d.getLogID()) && (s.getTargetID().equals(d.getTargetID()))) {
                    diffs = new LogDescriptor(s.getTargetID(), s.getLogID(), d.getRangeSet().diffDest(s.getRangeSet()));
                }
            }
            descriptors.add(diffs);
        }
        return descriptors;
    }

    /**
     * See {@link DeploymentRepository#getDeploymentVersion(java.lang.String)}.
     */
    DeploymentVersionObject getMostRecentDeploymentVersion(String targetID) {
        return m_deploymentRepository.getMostRecentDeploymentVersion(targetID);
    }

    /**
     * Based on the information in this stateful object, creates a <code>TargetObject</code>
     * in the <code>TargetRepository</code>.
     * This function is intended to be used for targets which are not yet represented
     * in the <code>TargetRepository</code>; if they already are, an <code>IllegalArgumentException</code>
     * will be thrown.
     * 
     * @param targetID A string representing the ID of the new target.
     */
    void register(String targetID) {
        Map<String, String> attr = new HashMap<String, String>();
        attr.put(TargetObject.KEY_ID, targetID);
        Map<String, String> tags = new HashMap<String, String>();
        m_targetRepository.create(attr, tags);
        getStatefulTargetObject(targetID).updateTargetObject(false);
    }

    /**
     * Notifies interested parties of a change to a <code>StatefulTargetObject</code>.
     * 
     * @param stoi The <code>StatefulTargetObject</code> which has changed.
     * @param topic A topic string for posting the event.
     * @param additionalProperties A Properties event, already containing some extra properties. If
     *        RepositoryObject.EVENT_ENTITY is used, it will be overwritten.
     */
    void notifyChanged(StatefulTargetObject stoi, String topic, Properties additionalProperties) {
        additionalProperties.put(RepositoryObject.EVENT_ENTITY, stoi);
        additionalProperties.put(SessionFactory.SERVICE_SID, m_sessionID);
        m_eventAdmin.postEvent(new Event(topic, (Dictionary) additionalProperties));
    }

    /**
     * Notifies interested parties of a change to a <code>StatefulTargetObject</code>.
     * 
     * @param stoi The <code>StatefulTargetObject</code> which has changed.
     * @param topic A topic string for posting the event.
     */
    void notifyChanged(StatefulTargetObject stoi, String topic) {
        notifyChanged(stoi, topic, new Properties());
    }

    /**
     * Reads the information sources to generate the stateful objects.
     */
    private void populate() {
        synchronized (m_repository) {
            List<StatefulTargetObjectImpl> touched = new ArrayList<StatefulTargetObjectImpl>();
            touched.addAll(parseTargetRepository());
            touched.addAll(parseAuditLog());

            // Now, it is possible we have not touched all objects. Find out which these are, and make
            // them check whether they should still exist.
            List<StatefulTargetObjectImpl> all = new ArrayList<StatefulTargetObjectImpl>(m_repository.values());
            all.removeAll(touched);
            for (StatefulTargetObjectImpl stoi : all) {
                stoi.updateTargetObject(false);
                stoi.updateDeploymentVersions(null);
                stoi.updateAuditEvents(true);
            }
            // Furthermore, for all those we _did_ see, we need to make sure their deployment versions
            // are up to date.
            for (StatefulTargetObjectImpl stoi : touched) {
                stoi.updateDeploymentVersions(null);
                stoi.updateTargetObject(true);
            }
        }
    }

    /**
     * Checks all inhabitants of the <code>TargetRepository</code> to see
     * whether we already have a stateful representation of them.
     * 
     * @param needsVerify states whether the objects which are 'touched' by this
     *        actions should verify their existence.
     * @return A list of all the target objects that have been touched by this action.
     */
    private List<StatefulTargetObjectImpl> parseTargetRepository() {
        List<StatefulTargetObjectImpl> result = new ArrayList<StatefulTargetObjectImpl>();
        for (TargetObject to : m_targetRepository.get()) {
            StatefulTargetObjectImpl stoi = getStatefulTargetObject(to.getID());
            if (stoi == null) {
                result.add(createStateful(to.getID()));
            }
            else {
                result.add(stoi);
                stoi.updateTargetObject(false);
            }
        }
        return result;
    }

    /**
     * Checks the audit log to see whether we already have a
     * stateful object for all targets mentioned there.
     * 
     * @param needsVerify states whether the objects which are 'touched' by this
     *        actions should verify their existence.
     */
    private List<StatefulTargetObjectImpl> parseAuditLog() {
        List<StatefulTargetObjectImpl> result = new ArrayList<StatefulTargetObjectImpl>();
        List<LogDescriptor> descriptors = null;
        try {
            descriptors = m_auditLogStore.getDescriptors();
        }
        catch (IOException e) {
            // Not much to do.
        }
        if (descriptors == null) {
            // There is no audit log available, or it failed getting the log descriptors.
            return result;
        }

        Set<String> targetIDs = new HashSet<String>();
        for (LogDescriptor l : descriptors) {
            targetIDs.add(l.getTargetID());
        }

        /*
         * Note: the parsing of the audit log and the creation/notification of the
         * stateful objects has been separated, to prevent calling updateAuditEvents()
         * multiple times on targets which have more than one log.
         */
        synchronized (m_repository) {
            for (String targetID : targetIDs) {
                StatefulTargetObjectImpl stoi = getStatefulTargetObject(targetID);
                if (stoi == null) {
                    result.add(createStateful(targetID));
                }
                else {
                    result.add(stoi);
                    stoi.updateAuditEvents(false);
                }
            }
        }
        return result;
    }

    /**
     * Approves the changes that will happen to the target based on the
     * changes in the shop by generating a new deployment version.
     * 
     * @param targetID A string representing a target ID.
     * @return The version identifier of the new deployment package.
     * @throws java.io.IOException When there is a problem generating the deployment version.
     */
    String approve(String targetID) throws IOException {
        return generateDeploymentVersion(targetID).getVersion();
    }

    /**
     * Generates an array of bundle URLs which have to be deployed on
     * the target, given the current state of the shop.
     * TODO: In the future, we want to add support for multiple shops.
     * TODO: Is this prone to concurrency issues with changes distribution- and
     * feature objects?
     * 
     * @param targetID A string representing a target.
     * @return An array of artifact URLs.
     * @throws java.io.IOException When there is a problem processing an artifact for deployment.
     */
    DeploymentArtifact[] getNecessaryDeploymentArtifacts(String targetID, String version) throws IOException {
        TargetObject to = getTargetObject(targetID);

        Map<ArtifactObject, String> bundles = new HashMap<ArtifactObject, String>();
        Map<ArtifactObject, String> artifacts = new HashMap<ArtifactObject, String>();
        Map<ArtifactObject, Map<FeatureObject, List<DistributionObject>>> path =
            new HashMap<ArtifactObject, Map<FeatureObject, List<DistributionObject>>>();

        // First, find all basic bundles and artifacts. An while we're traversing the
        // tree of objects, build the tree of properties.
        if (to != null) {
            for (DistributionObject distribution : to.getDistributions()) {
                for (FeatureObject feature : distribution.getFeatures()) {
                    for (ArtifactObject artifact : feature.getArtifacts()) {
                        if (m_bundleHelper.canUse(artifact)) {
                            bundles.put(artifact, m_bundleHelper.getResourceProcessorPIDs(artifact));
                        }
                        else {
                            artifacts.put(artifact, artifact.getProcessorPID());
                        }
                        Map<FeatureObject, List<DistributionObject>> featureToDistribution = path.get(artifact);
                        if (featureToDistribution == null) {
                            featureToDistribution = new HashMap<FeatureObject, List<DistributionObject>>();
                            path.put(artifact, featureToDistribution);
                        }
                        List<DistributionObject> distributions = featureToDistribution.get(feature);
                        if (distributions == null) {
                            distributions = new ArrayList<DistributionObject>();
                            featureToDistribution.put(feature, distributions);
                        }
                        distributions.add(distribution);
                    }
                }
            }
        }

        // Find all processors
        Map<String, ArtifactObject> allProcessors = new HashMap<String, ArtifactObject>();
        for (ArtifactObject bundle : m_artifactRepository.getResourceProcessors()) {
            allProcessors.put(m_bundleHelper.getResourceProcessorPIDs(bundle), bundle);
        }

        // Determine all resource processors we need
        for (String processor : artifacts.values()) {
            if (!bundles.containsValue(processor)) {
                ArtifactObject bundle = allProcessors.get(processor);
                if (bundle == null) {
                    m_log.log(LogService.LOG_ERROR, "Unable to create deployment version: there is no resource processing bundle available that publishes " + processor);
                    throw new IllegalStateException("Unable to create deployment version: there is no resource processing bundle available that publishes " + processor);
                }
                bundles.put(bundle, processor);
            }
        }

        List<DeploymentArtifact> result = new ArrayList<DeploymentArtifact>();

        for (ArtifactObject bundle : bundles.keySet()) {
            Map<String, String> directives = new HashMap<String, String>();
            if (m_bundleHelper.isResourceProcessor(bundle)) {
                // it's a resource processor, mark it as such.
                directives.put(DeploymentArtifact.DIRECTIVE_ISCUSTOMIZER, "true");
            }
            directives.put(BundleHelper.KEY_SYMBOLICNAME, m_bundleHelper.getSymbolicName(bundle));
            String bundleVersion = m_bundleHelper.getVersion(bundle);
            if (bundleVersion != null) {
                directives.put(BundleHelper.KEY_VERSION, bundleVersion);
            }

            directives.put(DeploymentArtifact.DIRECTIVE_KEY_BASEURL, bundle.getURL());

            String repositoryPath = getRepositoryPath(bundle, path);
            if (repositoryPath != null) {
                directives.put(DeploymentArtifact.REPOSITORY_PATH, repositoryPath);
            }

            result.add(m_deploymentRepository.createDeploymentArtifact(bundle.getURL(), directives));
        }

        for (ArtifactObject artifact : artifacts.keySet()) {
            Map<String, String> directives = new HashMap<String, String>();
            directives.put(DeploymentArtifact.DIRECTIVE_KEY_PROCESSORID, artifact.getProcessorPID());
            directives.put(DeploymentArtifact.DIRECTIVE_KEY_BASEURL, artifact.getURL());
            if (artifact.getResourceId() != null) {
                directives.put(DeploymentArtifact.DIRECTIVE_KEY_RESOURCE_ID, artifact.getResourceId());
            }

            String repositoryPath = getRepositoryPath(artifact, path);
            if (repositoryPath != null) {
                directives.put(DeploymentArtifact.REPOSITORY_PATH, repositoryPath);
            }
            result.add(m_deploymentRepository.createDeploymentArtifact(
                m_artifactRepository.preprocessArtifact(artifact, to, targetID, version), directives));
        }

        return result.toArray(new DeploymentArtifact[result.size()]);
    }

    private String getRepositoryPath(ArtifactObject artifact,
        Map<ArtifactObject, Map<FeatureObject, List<DistributionObject>>> path) {
        StringBuilder builder = new StringBuilder();
        Map<FeatureObject, List<DistributionObject>> featureToDistribution = path.get(artifact);
        if (featureToDistribution != null) {
            for (Entry<FeatureObject, List<DistributionObject>> entry : featureToDistribution.entrySet()) {
                for (DistributionObject distribution : entry.getValue()) {
                    builder.append(entry.getKey().getName()).append(';').append(distribution.getName()).append(',');
                }
            }
        }
        else {
            return null;
        }
        builder.setLength(builder.length() - 1);
        return builder.toString();
    }

    /**
     * Quick method to find all artifacts that need to be deployed to a target.
     */
    ArtifactObject[] getNecessaryArtifacts(String targetID) {
        List<ArtifactObject> result = new ArrayList<ArtifactObject>();
        TargetObject to = getTargetObject(targetID);

        Map<String, ArtifactObject> allProcessors = new HashMap<String, ArtifactObject>();
        for (ArtifactObject bundle : m_artifactRepository.getResourceProcessors()) {
            allProcessors.put(m_bundleHelper.getResourceProcessorPIDs(bundle), bundle);
        }

        if (to != null) {
            for (DistributionObject distribution : to.getDistributions()) {
                for (FeatureObject feature : distribution.getFeatures()) {
                    for (ArtifactObject artifact : feature.getArtifacts()) {
                        result.add(artifact);
                        if (!m_bundleHelper.canUse(artifact)) {
                            ArtifactObject processor = allProcessors.get(artifact.getProcessorPID());
                            if (processor == null) {
                                // this means we cannot create a useful version; return null.
                                return null;
                            }
                            result.add(processor);
                        }
                    }
                }
            }
        }

        return result.toArray(new ArtifactObject[result.size()]);
    }

    /**
     * Generates a new deployment version for the the given target,
     * based on the artifacts it is linked to by the distributions it is
     * associated to.
     * 
     * @param targetID A string representing a target.
     * @return A new DeploymentVersionObject, representing this new version for the target.
     * @throws java.io.IOException When there is a problem determining the artifacts to be deployed.
     */
    DeploymentVersionObject generateDeploymentVersion(String targetID) throws IOException {
        Map<String, String> attr = new HashMap<String, String>();
        attr.put(DeploymentVersionObject.KEY_TARGETID, targetID);
        Map<String, String> tags = new HashMap<String, String>();

        DeploymentVersionObject mostRecentDeploymentVersion = getMostRecentDeploymentVersion(targetID);
        String nextVersion;
        if (mostRecentDeploymentVersion == null) {
            nextVersion = nextVersion(null);
        }
        else {
            nextVersion = nextVersion(mostRecentDeploymentVersion.getVersion());
        }
        attr.put(DeploymentVersionObject.KEY_VERSION, nextVersion);

        synchronized (m_repository) {
            DeploymentVersionObject result = m_deploymentRepository.create(attr, tags, getNecessaryDeploymentArtifacts(targetID, nextVersion));

            StatefulTargetObjectImpl stoi = getStatefulTargetObject(targetID);
            if (stoi == null) {
                createStateful(targetID);
            }
            else {
                stoi.updateDeploymentVersions(result);
            }

            return result;
        }
    }

    /**
     * Generates the next version, based on the version passed in.
     * The version is assumed to be an OSGi-version; for now, the next
     * 'major' version is generated. In the future, we might want to do
     * 'smarter' things here, like checking the impact of a new version
     * and use the minor and micro versions, or attach some qualifier.
     * 
     * @param version A string representing a deployment version's version.
     * @return A string representing the next version.
     */
    private static String nextVersion(String version) {
        try {
            Version v = new Version(version);
            Version result = new Version(v.getMajor() + 1, 0, 0);
            return result.toString();
        }
        catch (Exception iae) {
            // Basically, if anything goes wrong, we assume we want to start a new version at 1.
            return "1.0.0";
        }
    }

    public void handleEvent(Event event) {
        String topic = event.getTopic();
        if (TargetObject.TOPIC_ADDED.equals(topic)) {
            synchronized (m_repository) {
                String id = ((TargetObject) event.getProperty(RepositoryObject.EVENT_ENTITY)).getID();
                StatefulTargetObjectImpl stoi = getStatefulTargetObject(id);
                if (stoi == null) {
                    createStateful(id);
                }
                else {
                    stoi.updateTargetObject(true);
                }
            }
        }
        else if (TargetObject.TOPIC_REMOVED.equals(topic)) {
            synchronized (m_repository) {
                String id = ((TargetObject) event.getProperty(RepositoryObject.EVENT_ENTITY)).getID();
                StatefulTargetObjectImpl stoi = getStatefulTargetObject(id);
                // if the stateful target is already gone; we don't have to do anything...
                if (stoi != null) {
                    stoi.updateTargetObject(true);
                }
            }
        }
        else if (DeploymentVersionObject.TOPIC_ADDED.equals(topic) || DeploymentVersionObject.TOPIC_REMOVED.equals(topic)) {
            synchronized (m_repository) {
                DeploymentVersionObject deploymentVersionObject = ((DeploymentVersionObject) event.getProperty(RepositoryObject.EVENT_ENTITY));
                String id = deploymentVersionObject.getTargetID();
                StatefulTargetObjectImpl stoi = getStatefulTargetObject(id);
                if (stoi == null) {
                    createStateful(id);
                }
                else {
                    stoi.updateDeploymentVersions(deploymentVersionObject);
                }
            }
        }
        else if (RepositoryAdmin.TOPIC_LOGIN.equals(topic) || RepositoryAdmin.TOPIC_REFRESH.equals(topic)) {
            synchronized (m_repository) {
                populate();
            }
        }
        else {
            // Something else has changed; however, the entire shop may have an influence on
            // any target, so recheck everything.
            synchronized (m_repository) {
                for (StatefulTargetObjectImpl stoi : m_repository.values()) {
                    stoi.determineStatus();
                }
            }
        }
    }

    boolean needsNewVersion(ArtifactObject artifact, String targetID, String version) {
        return m_artifactRepository.needsNewVersion(artifact, getTargetObject(targetID), targetID, version);
    }
}