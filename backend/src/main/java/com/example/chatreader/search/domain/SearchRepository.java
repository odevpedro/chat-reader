package com.example.chatreader.search.domain;

import java.util.List;

/** Porta de busca textual (implementada com full-text do PostgreSQL). */
public interface SearchRepository {

    List<SearchHit> search(String query, int page, int size);

    long count(String query);
}
