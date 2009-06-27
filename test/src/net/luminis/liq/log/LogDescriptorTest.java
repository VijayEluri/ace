package net.luminis.liq.log;

import static net.luminis.liq.test.utils.TestUtils.UNIT;
import net.luminis.liq.log.LogDescriptor;
import net.luminis.liq.repository.SortedRangeSet;

import org.testng.annotations.Test;

public class LogDescriptorTest {

    @Test(groups = { UNIT })
    public void serializeDescriptor() {
        LogDescriptor descriptor = new LogDescriptor("gwid", 1, new SortedRangeSet("2-3"));
        assert descriptor.toRepresentation().equals("gwid,1,2-3") : "The representation of our descriptor is incorrect:" + descriptor.toRepresentation();
    }

    @Test(groups = { UNIT })
    public void deserializeDescriptor() {
        LogDescriptor descriptor = new LogDescriptor("gwid,1,2-3");
        assert descriptor.getGatewayID().equals("gwid") : "Gateway ID not correctly parsed.";
        assert descriptor.getLogID() == 1 : "Log ID not correctly parsed.";
        assert descriptor.getRangeSet().toRepresentation().equals("2-3") : "There should be nothing in the diff between the set in the descriptor and the check-set.";
    }

    @Test(groups = { UNIT })
    public void deserializeMultiRangeDescriptor() {
        LogDescriptor descriptor = new LogDescriptor("gwid,1,1-4$k6$k8$k10-20");
        assert descriptor.getGatewayID().equals("gwid") : "Gateway ID not correctly parsed.";
        assert descriptor.getLogID() == 1 : "Log ID not correctly parsed.";
        String representation = descriptor.getRangeSet().toRepresentation();
        assert representation.equals("1-4,6,8,10-20") : "There should be nothing in the diff between the set in the descriptor and the check-set, but we parsed: " + representation;
    }

    @Test(groups = { UNIT })
    public void deserializeMultiRangeDescriptorWithFunnyGWID() {
        String line = "gw$$id,1,1-4$k6$k8$k10-20";
        LogDescriptor descriptor = new LogDescriptor(line);
        assert descriptor.getGatewayID().equals("gw$id") : "Gateway ID not correctly parsed.";
        assert descriptor.getLogID() == 1 : "Log ID not correctly parsed.";
        assert line.equals(descriptor.toRepresentation()) : "Converting the line back to the representation failed.";
        String representation = descriptor.getRangeSet().toRepresentation();
        assert representation.equals("1-4,6,8,10-20") : "There should be nothing in the diff between the set in the descriptor and the check-set, but we parsed: " + representation;
    }

    @Test(groups = { UNIT }, expectedExceptions = IllegalArgumentException.class)
    public void deserializeInvalidDescriptor() throws Exception {
        new LogDescriptor("invalidStringRepresentation");
    }
}
