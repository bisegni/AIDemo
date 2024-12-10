package com.example.aidemo.controller;

import java.time.LocalDateTime;
import java.util.List;

public record AnswerDTO(
        Boolean done,
        String response,
        LocalDateTime created_at,
        List<RelatedDocumentDTO> relatedDocument
) {}
