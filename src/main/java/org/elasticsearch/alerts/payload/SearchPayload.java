/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.alerts.payload;

import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.alerts.Alert;
import org.elasticsearch.alerts.support.AlertUtils;
import org.elasticsearch.alerts.support.init.proxy.ClientProxy;
import org.elasticsearch.alerts.support.init.proxy.ScriptServiceProxy;
import org.elasticsearch.alerts.trigger.Trigger;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.script.ExecutableScript;
import org.elasticsearch.script.ScriptService;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 *
 */
public class SearchPayload implements Payload {

    public static final String TYPE = "search";

    protected final ESLogger logger;
    protected final ScriptServiceProxy scriptService;
    protected final ClientProxy client;
    protected final SearchRequest request;

    public SearchPayload(ESLogger logger, ScriptServiceProxy scriptService, ClientProxy client, SearchRequest request) {
        this.logger = logger;
        this.scriptService = scriptService;
        this.client = client;
        this.request = request;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Map<String, Object> execute(Alert alert, Trigger.Result result, DateTime scheduledFireTime, DateTime fireTime) throws IOException {
        SearchRequest req = createRequest(request, scheduledFireTime, fireTime, result.data());
        SearchResponse resp = client.search(req).actionGet();
        return AlertUtils.responseToData(resp);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        return null;
    }

    // right now the payload request is not templated by the given trigger data... we should though think how to do that
    public SearchRequest createRequest(SearchRequest request, DateTime scheduledFireTime, DateTime fireTime, Map<String, Object> data) throws IOException {
        SearchRequest triggerSearchRequest = new SearchRequest(request)
                .indicesOptions(request.indicesOptions())
                .indices(request.indices());
        if (Strings.hasLength(request.source())) {
            Map<String, String> templateParams = new HashMap<>();
            templateParams.put(AlertUtils.SCHEDULED_FIRE_TIME_VARIABLE_NAME, AlertUtils.format(scheduledFireTime));
            templateParams.put(AlertUtils.FIRE_TIME_VARIABLE_NAME, AlertUtils.format(fireTime));
            String requestSource = XContentHelper.convertToJson(request.source(), false);
            ExecutableScript script = scriptService.executable("mustache", requestSource, ScriptService.ScriptType.INLINE, templateParams);
            triggerSearchRequest.source((BytesReference) script.unwrap(script.run()), false);
        } else if (request.templateName() != null) {
            MapBuilder<String, String> templateParams = MapBuilder.newMapBuilder(request.templateParams())
                    .put(AlertUtils.SCHEDULED_FIRE_TIME_VARIABLE_NAME, AlertUtils.format(scheduledFireTime))
                    .put(AlertUtils.FIRE_TIME_VARIABLE_NAME, AlertUtils.format(fireTime));
            triggerSearchRequest.templateParams(templateParams.map());
            triggerSearchRequest.templateName(request.templateName());
            triggerSearchRequest.templateType(request.templateType());
        } else {
            throw new PayloadException("search requests needs either source or template name");
        }
        return triggerSearchRequest;
    }

    public static class Parser extends AbstractComponent implements Payload.Parser<SearchPayload> {

        protected final ScriptServiceProxy scriptService;
        protected final ClientProxy client;

        public Parser(Settings settings, ScriptServiceProxy scriptService, ClientProxy client) {
            super(settings);
            this.scriptService = scriptService;
            this.client = client;
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public SearchPayload parse(XContentParser parser) throws IOException {
            SearchRequest request = AlertUtils.readSearchRequest(parser, AlertUtils.DEFAULT_PAYLOAD_SEARCH_TYPE);
            return new SearchPayload(logger, scriptService, client, request);
        }
    }

}
