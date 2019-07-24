/*
 * Copyright (C) 2001-2015 Food and Agriculture Organization of the
 * United Nations (FAO-UN), United Nations World Food Programme (WFP)
 * and United Nations Environment Programme (UNEP)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or (at
 * your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
 *
 * Contact: Jeroen Ticheler - FAO - Viale delle Terme di Caracalla 2,
 * Rome - Italy. email: geonetwork@osgeo.org
 */

package org.fao.geonet.index.es;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.nio.conn.SchemeIOSessionStrategy;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.apache.http.ssl.SSLContextBuilder;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.indices.analyze.AnalyzeRequest;
import org.elasticsearch.action.admin.indices.analyze.AnalyzeResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.QueryStringQueryBuilder;
import org.elasticsearch.index.query.WrapperQueryBuilder;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.index.reindex.DeleteByQueryRequest;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortBuilder;
import org.fao.geonet.utils.Log;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;


/**
 * Client to connect to Elasticsearch
 */
public class EsRestClient implements InitializingBean {
    private static EsRestClient instance;

    private RestHighLevelClient client;

    @Value("${es.url}")
    private String serverUrl;

    @Value("${es.protocol}")
    private String serverProtocol;

    @Value("${es.host}")
    private String serverHost;

    @Value("${es.port}")
    private String serverPort;

    @Value("${es.username}")
    private String username;

    @Value("${es.password}")
    private String password;

    private boolean activated = false;

    public static EsRestClient get() {
        return instance;
    }

    public RestHighLevelClient getClient() throws Exception {
        return client;
    }

    public String getDashboardAppUrl() {
        return dashboardAppUrl;
    }

    public void setDashboardAppUrl(String dashboardAppUrl) {
        this.dashboardAppUrl = dashboardAppUrl;
    }

    @Value("${kb.url}")
    private String dashboardAppUrl;


    @Override
    public void afterPropertiesSet() throws Exception {
        if (StringUtils.isNotEmpty(serverUrl)) {
            RestClientBuilder builder = RestClient.builder(new HttpHost(serverHost, Integer.parseInt(serverPort), serverProtocol));

            if (serverUrl.startsWith("https://")) {
                SSLContext sslContext = new SSLContextBuilder().loadTrustMaterial(
                    null, new TrustStrategy() {
                        public boolean isTrusted(X509Certificate[] arg0, String arg1) throws CertificateException {
                            return true;
                        }
                    }).build();
                // skip hostname checks
                HostnameVerifier hostnameVerifier = NoopHostnameVerifier.INSTANCE;
                SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(sslContext, hostnameVerifier);
                SchemeIOSessionStrategy httpsIOSessionStrategy = new SSLIOSessionStrategy(sslContext, hostnameVerifier);

                if (StringUtils.isNotEmpty(username) && StringUtils.isNotEmpty(password)) {
                    final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                    credentialsProvider.setCredentials(AuthScope.ANY,
                        new UsernamePasswordCredentials(username, password));

                    builder.setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
                        @Override
                        public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
                            return httpClientBuilder.setSSLContext(sslContext).setDefaultCredentialsProvider(credentialsProvider);
                        }
                    });
                } else {
                    builder.setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
                        @Override
                        public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
                            return httpClientBuilder.setSSLContext(sslContext);
                        }
                    });
                }
            } else {
                if (StringUtils.isNotEmpty(username) && StringUtils.isNotEmpty(password)) {
                    final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                    credentialsProvider.setCredentials(AuthScope.ANY,
                        new UsernamePasswordCredentials(username, password));

                    builder.setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
                        @Override
                        public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
                            return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                        }
                    });
                }
            }
            client = new RestHighLevelClient(builder);

            synchronized (EsRestClient.class) {
                instance = this;
            }
            activated = true;
        } else {
            Log.debug("geonetwork.index", String.format(
                "No Elasticsearch URL defined '%s'. "
                    + "Check configuration.", this.serverUrl));
        }
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public EsRestClient setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
        return this;
    }

    public String getUsername() {
        return username;
    }

    public EsRestClient setUsername(String username) {
        this.username = username;
        return this;
    }

    public String getPassword() {
        return password;
    }

    public EsRestClient setPassword(String password) {
        this.password = password;
        return this;
    }

    public static final String ROUTING_KEY = "101";

    public BulkResponse bulkRequest(String index, Map<String, String> docs) throws IOException {
        if (!activated) {
            throw new IOException("Index not yet activated.");
        }

        BulkRequest request = new BulkRequest();

        Iterator iterator = docs.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, String> entry = (Map.Entry) iterator.next();
            request.add(new IndexRequest(index).id(entry.getKey())
                .source(entry.getValue(), XContentType.JSON));
                // https://www.elastic.co/fr/blog/customizing-your-document-routing
                // For features & record search we need to route the record
                // document to the same place as the features in order to make join
//                .routing(ROUTING_KEY));
        }
        try {
            return client.bulk(request, RequestOptions.DEFAULT);
        } catch (IOException e) {
            e.printStackTrace();
            throw e;
        }
//        BulkRequestBuilder bulkRequestBuilder = client.prepareBulk();
//        BulkResponse response = null;
//        int counter = 0;
//
//        Map<String, String> errors = new HashMap<>();
//        Iterator iterator = docs.keySet().iterator();
//        while (iterator.hasNext()) {
//            String id = (String)iterator.next();
//            try {
//
//                bulkRequestBuilder.add(
//                    client.prepareIndex(index, index, id).setSource(docs.get(id))
//                );
//                counter ++;
//
//                if (bulkRequestBuilder.numberOfActions() % commitInterval == 0) {
//                    response = bulkRequestBuilder.execute().actionGet();
//                    logger.info(String.format(
//                        "Importing %s: %d actions performed. Has errors: %s",
//                        index,
//                        counter,
//                        response.hasFailures()
//                    ));
//                    if (response.hasFailures()) {
//                        errors.put(counter + "", response.buildFailureMessage());
//                        success = false;
//                    }
//                    bulkRequestBuilder = client.prepareBulk();
//                }
//            } catch (Exception ex) {
//                ex.printStackTrace();
//            }
//        }
//        if (bulkRequestBuilder.numberOfActions() > 0) {
//            bulkRequestBuilder
//                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
//            response = bulkRequestBuilder.execute().actionGet();
//            logger.info(String.format(
//                "Importing %s: %d actions performed. Has errors: %s",
//                index,
//                counter,
//                response.hasFailures()
//            ));
//            if (response.hasFailures()) {
//                errors.put(counter + "", response.buildFailureMessage());
//                success = false;
//            }
//        }
    }

//
//    public void bulkRequestAsync(Bulk.Builder bulk , JestResultHandler<BulkResult> handler) {
//        client.executeAsync(bulk.build(), handler);
//
//    }
//
//    public BulkResult bulkRequestSync(Bulk.Builder bulk) throws IOException {
//        return client.execute(bulk.build());
//    }


    /**
     * Query using Lucene query syntax.
     */
    public SearchResponse query(String index, String luceneQuery, String filterQuery, Set<String> includedFields,
                                int from, int size) throws Exception {
        return query(index, luceneQuery, filterQuery, includedFields, from, size, null);
    }

    public SearchResponse query(String index, String luceneQuery, String filterQuery, Set<String> includedFields,
                                int from, int size, List<SortBuilder<FieldSortBuilder>> sort) throws Exception {
        final QueryBuilder query = QueryBuilders.queryStringQuery(luceneQuery);
        QueryBuilder filter = null;
        if (StringUtils.isNotEmpty(filterQuery)) {
            filter = QueryBuilders.queryStringQuery(filterQuery);
        }
        return query(index, query, filter, includedFields, from, size, sort);
    }

    /**
     * Query using JSON elastic query
     */
    public SearchResponse query(String index, JsonNode jsonQuery, QueryBuilder postFilterBuilder, Set<String> includedFields,
                                int from, int size) throws Exception {
        final QueryBuilder query = QueryBuilders.wrapperQuery(String.valueOf(jsonQuery));
        return query(index, query, postFilterBuilder, includedFields, from, size, null);
    }

    public SearchResponse query(String index, QueryBuilder queryBuilder, QueryBuilder postFilterBuilder, Set<String> includedFields,
                                int from, int size, List<SortBuilder<FieldSortBuilder>> sort) throws Exception {
        if (!activated) {
            return null;
        }

        // TODOES: Add permission if index is gn-records
        // See EsHTTPProxy#addUserInfo
        SearchRequest searchRequest = new SearchRequest();
        searchRequest.indices(index);
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.query(queryBuilder);
        searchSourceBuilder.fetchSource(includedFields.toArray(new String[includedFields.size()]), null);
        searchSourceBuilder.from(from);
        searchSourceBuilder.size(size);
        if (postFilterBuilder != null) {
            searchSourceBuilder.postFilter(postFilterBuilder);
        }

        if ((sort != null) && (!sort.isEmpty())) {
            sort.forEach(s -> searchSourceBuilder.sort(s));
        }

//        searchSourceBuilder.sort(new FieldSortBuilder("_id").order(SortOrder.ASC));
        searchRequest.source(searchSourceBuilder);

        SearchResponse searchResponse = client.search(searchRequest, RequestOptions.DEFAULT);
        if (searchResponse.status().getStatus() == 200) {
            return searchResponse;
        } else {
            throw new IOException(String.format(
                "Error during querying index. Errors is '%s'.", searchResponse.status().toString()
            ));
        }
    }


    public String deleteByQuery(String index, String query) throws Exception {
        if (!activated) {
            return "";
        }

        DeleteByQueryRequest request = new DeleteByQueryRequest(index);
        request.setQuery(new QueryStringQueryBuilder(query));

        final BulkByScrollResponse deleteByQueryResponse = client.deleteByQuery(request, RequestOptions.DEFAULT);

        if (deleteByQueryResponse.getStatus().getDeleted() >= 0) {
            return String.format("Record removed. %s.", deleteByQueryResponse.getStatus().getDeleted());
        } else {
            throw new IOException(String.format(
                "Error during removal. Errors is '%s'.", deleteByQueryResponse.getStatus().getReasonCancelled()
            ));
        }
    }

    /**
     * Query the index for a specific record and return values for a set of fields.
     */
    public Map<String, String> getFieldsValues(String index, String id, Set<String> fields) throws IOException {
        if (!activated) {
            return null;
        }

        Map<String, String> fieldValues = new HashMap<>(fields.size());
        try {
            String query = String.format("_id:%s uuid:%s", id, id);
            // TODO: Check maxRecords
            final SearchResponse searchResponse = this.query(index, query, null, fields, 0, 1000, null);
            if (searchResponse.status().getStatus() == 200) {
                if (searchResponse.getHits().getTotalHits().value == 1) {
                    final SearchHit[] hits = searchResponse.getHits().getHits();

                    fields.forEach(f -> {
                        final Object o = hits[0].getSourceAsMap().get(f);
                        if (o instanceof String) {
                            fieldValues.put(f, (String) o);
                        }
                    });
                } else {
                    throw new IOException(String.format(
                        "Your query '%s' returned more than one record, %d in fact. Can't retrieve field values for more than one record.",
                        query,
                        searchResponse.getHits().getTotalHits()
                    ));
                }
            } else {
                throw new IOException(String.format(
                    "Error during fields value retrival. Status is '%s'.", searchResponse.status().getStatus()
                ));
            }
        } catch (Exception e) {
            throw new IOException(String.format(
                "Error during fields value retrival. Errors is '%s'.", e.getMessage()
            ));
        }
        return fieldValues;
    }


    /**
     * Analyze a field and a value against the index
     * or query phase and return the first value generated
     * by the specified analyzer. For now mainly used for
     * synonyms analysis.
     * <p>
     * See https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-analyze.html
     * {
     * "tokens" : [
     * {
     * "token" : "area management/restriction/regulation zones and reporting units",
     * "start_offset" : 0,
     * "end_offset" : 64,
     * "type" : "word",
     * "position" : 0
     * }
     * ]
     * }
     * or when a synonym is found
     * {
     * "tokens" : [
     * {
     * "token" : "elevation",
     * "start_offset" : 0,
     * "end_offset" : 8,
     * "type" : "SYNONYM",
     * "position" : 0
     * }
     * ]
     * }
     *
     * @param fieldValue The field value to analyze
     * @return The analyzed string value if found or empty text if not found or if an exception occurred.
     */
    public static String analyzeField(String collection,
                                      String analyzer,
                                      String fieldValue) {

        AnalyzeRequest request = new AnalyzeRequest();
        request.index(collection);
        // Replace , as it is meaningful in synonym map format
        request.text(fieldValue.replace(",", ""));
        request.analyzer(analyzer);

        String analyzedValue = "";
        try {
            AnalyzeResponse response = EsRestClient.get().client.indices().analyze(request, RequestOptions.DEFAULT);

            final List<AnalyzeResponse.AnalyzeToken> tokens = response.getTokens();
            if (tokens.size() == 1) {
                final String type = tokens.get(0).getType();
                if ("SYNONYM".equals(type) || "word".equals(type)) {
                    return tokens.get(0).getTerm();
                }
                return "";
            } else {
                return "";
            }
        } catch (Exception ex) {
            return "";
        }
    }


    protected void finalize() {
        try {
            client.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // TODO: check index exist too
    public String getServerStatus() throws Exception {
        ClusterHealthRequest request = new ClusterHealthRequest();
        ClusterHealthResponse response = client.cluster().health(request, RequestOptions.DEFAULT);

        return response.getStatus().toString();
//        return getClient().ping(RequestOptions.DEFAULT);
    }
}
