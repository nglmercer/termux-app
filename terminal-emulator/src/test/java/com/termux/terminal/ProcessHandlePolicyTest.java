package com.termux.terminal;

import junit.framework.TestCase;

public class ProcessHandlePolicyTest extends TestCase {
    public void testConsentIsBoundToCreationAndRevokedAcrossRestarts() {
        ProcessHandlePolicy policy = new ProcessHandlePolicy();
        long before = policy.capture();
        assertFalse(policy.permits(before));
        policy.setEnabled(true);
        long first = policy.capture();
        assertTrue(policy.permits(first));
        assertFalse(policy.permits(before));
        policy.setEnabled(false);
        assertFalse(policy.permits(first));
        policy.setEnabled(true);
        assertFalse(policy.permits(first));
        assertTrue(policy.permits(policy.capture()));
    }
}
