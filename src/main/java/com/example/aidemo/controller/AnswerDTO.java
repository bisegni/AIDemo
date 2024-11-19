package com.example.aidemo.controller;

import java.util.List;

public record AnswerDTO(
        String content,
        List<RelatedDocumentDTO> relatedDocument
) {}
