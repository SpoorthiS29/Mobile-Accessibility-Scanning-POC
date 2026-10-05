package com.poc.a11y.atf.harness;

/**
 * Crops the current viewport bitmap onto a custom finding. Called from
 * {@link CustomHierarchyCheck#evaluate} at the moment a node is flagged,
 * so the check does not walk the tree again later.
 */
interface ViewportCropper {

    void crop(AtfIssueRecord record);
}
