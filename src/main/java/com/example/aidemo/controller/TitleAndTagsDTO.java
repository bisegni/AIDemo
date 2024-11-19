package com.example.aidemo.controller;

import java.util.List;

public record TitleAndTagsDTO(
        String title,
        List<String> tags
) {}
