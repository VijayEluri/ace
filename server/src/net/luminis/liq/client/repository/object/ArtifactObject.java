package net.luminis.liq.client.repository.object;

import java.util.List;

import net.luminis.liq.client.repository.RepositoryObject;

/**
 * Interface to an ArtifactObject. The basic functionality is defined by RepositoryObject, but extended for
 * artifact-specific information.
 */
public interface ArtifactObject extends RepositoryObject {
    /**
     * Key to be used in the <code>ArtifactObject</code>'s attributes.
     * Indicates the location of the persistent storage of the artifact.
     */
    public static final String KEY_URL = "url";
    /**
     * Key to be used in the <code>ArtifactObject</code>'s attributes.
     * Indicates the PID of the resource processor that should be used to process this artifact.
     * For a bundle, it is empty.
     */
    public static final String KEY_PROCESSOR_PID = "processorPid";
    /**
     * Key to be used in the <code>ArtifactObject</code>'s attributes.
     * Indicates the mimetype of this artifact. For artifacts which do not
     * have an adequately discriminating mimetype, it can be extended with
     * something non-standard.
     */
    public static final String KEY_MIMETYPE = "mimetype";
    /**
     * Key to be used in the <code>ArtifactObject</code>'s attributes.
     * Holds a human-readable name for this artifact.
     */
    public static final String KEY_ARTIFACT_NAME = "artifactName";
    /**
     * Key to be used in the <code>ArtifactObject</code>'s attributes.
     * Holds a human-readable description for this artifact.
     */
    public static final String KEY_ARTIFACT_DESCRIPTION = "artifactDescription";
    
    public static final String TOPIC_ENTITY_ROOT = ArtifactObject.class.getSimpleName() + "/";
    
    public static final String TOPIC_ADDED = PUBLIC_TOPIC_ROOT + TOPIC_ENTITY_ROOT + TOPIC_ADDED_SUFFIX;
    public static final String TOPIC_REMOVED = PUBLIC_TOPIC_ROOT + TOPIC_ENTITY_ROOT + TOPIC_REMOVED_SUFFIX;
    public static final String TOPIC_CHANGED = PUBLIC_TOPIC_ROOT + TOPIC_ENTITY_ROOT + TOPIC_CHANGED_SUFFIX;
    public static final String TOPIC_ALL = PUBLIC_TOPIC_ROOT + TOPIC_ENTITY_ROOT + TOPIC_ALL_SUFFIX;

    /**
     * Returns all <code>GroupObject</code>s this object is associated with. If there
     * are none, an empty list will be returned.
     */
    public List<GroupObject> getGroups();
    /**
     * Returns all associations this artifact has with a given group.
     */
    public List<Artifact2GroupAssociation> getAssociationsWith(GroupObject group);

    /**
     * Returns the mimetype of this artifact.
     */
    public String getMimetype();
    /**
     * Returns the PID of the resource processor of this artifact.
     */
    public String getProcessorPID();
    /**
     * Sets the PID of the resource processor of this artifact.
     */
    public void setProcessorPID(String processorPID);
    /**
     * Returns the URL to this artifact.
     */
    public String getURL();
    /**
     * Return a descriptive name for this object. May return <code>null</code>.
     */
    public String getName();
    /**
     * Returns a description for this object. May return <code>null</code>.
     */
    public String getDescription();
    /**
     * Sets a description for this artifact.
     */
    public void setDescription(String value);
}
