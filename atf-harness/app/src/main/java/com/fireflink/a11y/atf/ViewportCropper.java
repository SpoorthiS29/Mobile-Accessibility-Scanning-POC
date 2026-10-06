package com.fireflink.a11y.atf;

/**
 * Crops the current viewport bitmap onto a custom finding. Called from
 * {@link CustomHierarchyCheck#evaluate} at the moment a node is flagged,
 * so the check does not walk the tree again later.
 */
interface ViewportCropper {

    void crop(AtfIssueRecord record);
}
