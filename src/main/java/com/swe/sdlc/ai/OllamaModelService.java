package com.swe.sdlc.ai;

import com.swe.sdlc.config.OllamaModelCatalog;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Service;

@Service
public class OllamaModelService {
    private final ChatModel chatModel;
    private final OllamaModelCatalog modelCatalog;

    public OllamaModelService(ChatModel chatModel, OllamaModelCatalog modelCatalog) {
        this.chatModel = chatModel;
        this.modelCatalog = modelCatalog;
    }

    public String generatePatch(String requirement, String modelAlias) {
        String modelName = modelCatalog.resolve(modelAlias);
        String promptText = "Generate a proposed code change for the following requirement. "
                + "Return only the proposed source changes, without Markdown fences.\n\n"
                + requirement;
        Prompt prompt = new Prompt(promptText, OllamaChatOptions.builder().model(modelName).build());
        ChatResponse response = chatModel.call(prompt);

        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null
                || response.getResult().getOutput().getText().isBlank()) {
            throw new IllegalStateException("Ollama returned an empty response");
        }
        return response.getResult().getOutput().getText();
    }
}