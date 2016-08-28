package org.example.service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.transaction.Transactional;

import org.example.model.Page;
import org.example.model.QueryResult;
import org.example.model.search.Search;
import org.example.repository.PageRepository;
import org.example.repository.PageSearchRepository;
import org.example.repository.QueryResultRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SearchServiceImpl implements SearchService {

	@Autowired
	private SearchEngineOptimization engineOptimization;

	@Autowired
	private SearchEngine searchEngine;

	@Autowired
	private PageRepository pageRepository;

	@Autowired
	private QueryResultRepository resultRepository;

	@Autowired
	private PageSearchRepository pageSearchRepository;

	@Transactional
	@Override
	public List<Page> search(Search search) {

		List<String> queryList = Arrays.asList(search.getTags()).stream().map(String::toLowerCase).collect(Collectors.toList());
		String queryString = SearchEngineImpl.getQueryString(queryList);

		// From cache
		if (search.isCache()) {
			List<Page> cacheResult = engineOptimization.getCache().get(queryString);
			if (cacheResult != null && !cacheResult.isEmpty()) {
				return cacheResult;
			}
		}

		// From historic searches
		if (search.isIndex()) {
			QueryResult queryResult = this.resultRepository.findOne(queryString);
			if (queryResult != null) {
				List<Page> queryPages = this.pageRepository.findAll(queryResult.getPageNames());
				if (queryPages != null && !queryPages.isEmpty()) {
					return queryPages;
				}
			}
		}

		// Lucene search
		List<Page> persistentPages = pageSearchRepository.findPagesByTags(queryString);
		List<Page> indexedPages = searchEngine.indexing(persistentPages, queryList, queryString);

		// Persist to DB
		QueryResult result = new QueryResult();
		result.setQuery(queryString);
		result.setPageNames(indexedPages.stream().map(Page::getName).collect(Collectors.toList()));
		resultRepository.save(result);

		return indexedPages;
	}

	@Override
	public List<Page> save(List<Page> pages) {
		for (Page page : pages) {
			page.setTags(page.getTags().stream().map(String::toLowerCase).collect(Collectors.toList()));
		}
		return this.pageRepository.save(pages);
	}

	@Override
	public boolean delete(List<String> pages) {
		this.pageRepository.delete(this.pageRepository.findAll(pages));
		return true;
	}

	@Scheduled(fixedRate = 10 * 60 * 1000) // reIndex every 10 minutes
	@Override
	public void reIndex() {
		List<QueryResult> queryResults = this.resultRepository.findAll();
		for (QueryResult queryResult : queryResults) {
			List<String> tags = Arrays.stream(queryResult.getQuery().split("_")).collect(Collectors.toList());
			List<Page> pages = this.pageRepository.findAll(queryResult.getPageNames());
			this.searchEngine.indexing(pages, tags, queryResult.getQuery());
		}
	}

	@Override
	public List<Page> getAll() {
		return this.pageRepository.findAll();
	}

	@Override
	public Map<String, Boolean> clear() {
		Map<String, Boolean> result = new HashMap<>();
		this.engineOptimization.getCache().clear();
		result.put("in_memory_cache", true);
		this.resultRepository.deleteAll();
		result.put("page_indices", true);
		result.put("lucene_indices", this.pageSearchRepository.flushAllIndices());
		return result;
	}

}