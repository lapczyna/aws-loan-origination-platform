package com.example.los.application.adapter.in.web.dto;

import java.util.List;

/**
 * A page of results.
 *
 * @param items      the page contents
 * @param page       zero-based page number
 * @param size       page size
 * @param totalItems total number of matching records
 * @param totalPages total number of pages at this size
 * @param hasNext    whether a further page exists
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages, boolean hasNext) {

    public PageResponse {
        items = List.copyOf(items);
    }
}
