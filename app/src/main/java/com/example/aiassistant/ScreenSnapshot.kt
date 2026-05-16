package com.example.aiassistant

data class NodeSnapshot(
    val text: String?,
    val contentDesc: String?,
    val className: String?,
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean
)

data class ScreenSnapshot(
    val packageName: String,
    /** Exact structural hash of every node — high precision, lower resilience. */
    val stableState: String,
    /**
     * Hash of only interactable nodes (clickable / scrollable / editable), sorted
     * so insertion order doesn't matter. More resilient to cosmetic layout changes
     * (ads, banners, decorative containers) than [stableState].
     */
    val looseState: String,
    val nodes: List<NodeSnapshot>,
    val textElements: List<String>
)
