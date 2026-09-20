# Phase 4 — Changed Files

1. SimpleJson.kt (NEW): Zero-dependency pure-Kotlin JSON parser and serializer.
2. ModelClient.kt (REFACTORED): Added ModelDecisionRequest, StructuredModelResult, ModelError, ErrorType, HttpTransport, OpenAiCompatibleClient, GoogleGeminiClient, DisabledModelClient.
3. ContextCompressor.kt: Added compressed prompt builder and token budget estimation.
4. ToolProtocol.kt: Added OpenAI schema serialization map for tools.
5. MainActivity.kt: Removed hardcoded AI provider references.
6. JarvisBackgroundVoiceService.kt: Removed hardcoded AI provider references.
7. DynamicPlanner.kt: Refactored to use decideNextActionStructured.
8. AgentOrchestrator.kt: Dynamically updates ModelClient on mission start.
9. JarvisModelClientReliabilityTest.kt (NEW): 14 unit tests for model client reliability.
