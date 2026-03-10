package io.hammerhead.pacepilot.ai

enum class LlmProvider(val displayName: String) {
    GEMINI("Gemini 2.0 Flash (recommended)"),
    DISABLED("Disabled — rule-based messages only"),
}
