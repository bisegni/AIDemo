package com.example.aidemo.controller;

import java.util.Map;

public record RelatedDocumentDTO(
        String originApplication,
        Map<String,String> metadata
) {}
