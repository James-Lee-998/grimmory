package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.booklore.service.translation.TranslationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/translate")
@Tag(name = "Translation", description = "Translate text selected in the reader through the configured LibreTranslate server")
public class TranslationController {

    private final TranslationService translationService;

    public record StatusResponse(boolean enabled) {
    }

    public record TranslateRequest(String text, String source, String target) {
    }

    @Operation(summary = "Translation status", description = "Whether a translation server is configured.", operationId = "translateStatus")
    @GetMapping("/status")
    public StatusResponse status() {
        return new StatusResponse(translationService.isEnabled());
    }

    @Operation(summary = "List languages", description = "Languages supported by the translation server.", operationId = "translateLanguages")
    @GetMapping("/languages")
    public List<TranslationService.Language> languages() {
        return translationService.languages();
    }

    @Operation(summary = "Translate text", description = "Translate text into the target language. Source defaults to auto-detect.", operationId = "translateText")
    @PostMapping
    public TranslationService.Translation translate(@RequestBody TranslateRequest request) {
        return translationService.translate(request.text(), request.source(), request.target());
    }
}
