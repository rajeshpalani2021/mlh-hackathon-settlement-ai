package com.settle.agent;

import java.util.List;

/** Structured output the investigator LLM must return. */
public record InvestigationResult(String rootCause, String explanation, double confidence,
                                  ProposedFix fix, List<String> wikiPagesUsed, String recipeUsed, String recipeDraft,
                                  List<String> unknownCodes) {

    public record ProposedFix(String field, String qualifier, String oldValue, String newValue) {}

    public InvestigationResult withFix(ProposedFix newFix, double newConfidence, String newExplanation) {
        return new InvestigationResult(rootCause, newExplanation, newConfidence, newFix, wikiPagesUsed, recipeUsed, recipeDraft, unknownCodes);
    }

    public InvestigationResult withRecipeUsed(String recipe) {
        return new InvestigationResult(rootCause, explanation, confidence, fix, wikiPagesUsed, recipe, recipeDraft, unknownCodes);
    }
}
