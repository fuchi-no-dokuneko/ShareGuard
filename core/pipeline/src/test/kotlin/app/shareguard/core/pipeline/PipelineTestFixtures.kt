package app.shareguard.core.pipeline

import app.shareguard.core.model.ExecutionContext
import app.shareguard.core.model.InputKind
import app.shareguard.core.model.OutputMode
import app.shareguard.core.model.SessionId
import app.shareguard.core.model.SourceHandle
import app.shareguard.core.model.WorkflowId
import app.shareguard.core.model.WorkflowVersion

internal fun executionContextFor(preset: PipelinePreset): ExecutionContext = ExecutionContext.create(
    sessionId = SessionId("test-session"),
    workflowId = WorkflowId(preset.presetId.lowercase()),
    workflowVersion = WorkflowVersion(preset.presetVersion.value),
    inputKind = preset.inputKind,
    requestedOutput = preset.outputMode,
    sourceHandle = SourceHandle("session-source"),
    assuranceCeiling = preset.assuranceCeiling,
)

internal fun executionContext(
    inputKind: InputKind = InputKind.TEXT,
    outputMode: OutputMode = OutputMode.TEXT,
): ExecutionContext = ExecutionContext.create(
    sessionId = SessionId("test-session"),
    workflowId = WorkflowId("test-workflow"),
    workflowVersion = WorkflowVersion(1),
    inputKind = inputKind,
    requestedOutput = outputMode,
    sourceHandle = SourceHandle("session-source"),
)
