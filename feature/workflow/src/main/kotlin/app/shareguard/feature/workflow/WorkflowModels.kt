package app.shareguard.feature.workflow

import app.shareguard.core.model.BlockExecutionStatus

data class WorkflowBlockUiModel(
    val blockId: String,
    val name: String,
    val status: BlockExecutionStatus,
    val findingCount: Int,
    val changeCount: Int,
    val warningCount: Int,
    val mandatory: Boolean,
    val inputType: String,
    val outputType: String,
) {
    init {
        require(blockId.isNotBlank() && name.isNotBlank())
        require(findingCount >= 0 && changeCount >= 0 && warningCount >= 0)
    }
}

data class WorkflowUiState(
    val presetName: String,
    val schemaVersion: Int,
    val inputLabel: String,
    val outputLabel: String,
    val blocks: List<WorkflowBlockUiModel>,
    val running: Boolean,
    val waitingForReview: Boolean,
    val fatalReason: String? = null,
) {
    init {
        require(schemaVersion > 0)
        require(blocks.map { it.blockId }.distinct().size == blocks.size)
    }
}
