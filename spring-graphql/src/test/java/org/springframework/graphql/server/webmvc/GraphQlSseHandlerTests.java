/*
 * Copyright 2020-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.graphql.server.webmvc;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import graphql.schema.DataFetcher;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.graphql.BookSource;
import org.springframework.graphql.GraphQlSetup;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.function.AsyncServerResponse;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Tests for {@link GraphQlSseHandler}.
 *
 * @author Brian Clozel
 */
class GraphQlSseHandlerTests {

	private static final List<HttpMessageConverter<?>> MESSAGE_READERS =
			List.of(new MappingJackson2HttpMessageConverter());

	private static final DataFetcher<?> SEARCH_DATA_FETCHER = env -> {
		String author = env.getArgument("author");
		return Flux.fromIterable(BookSource.books()).filter((book) -> book.getAuthor().getFullName().contains(author));
	};


	@Test
	void shouldRejectQueryOperations() throws Exception {
		GraphQlSseHandler handler = createSseHandler(SEARCH_DATA_FETCHER);
		MockHttpServletRequest request = createServletRequest("{ \"query\": \"{ bookById(id: 42) {name} }\"}");
		MockHttpServletResponse response = handleRequest(request, handler);

		assertThat(response.getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM_VALUE);
		assertThat(response.getContentAsString()).isEqualTo("""
				event:next
				data:{"errors":[{"message":"SSE handler supports only subscriptions","locations":[],"extensions":{"classification":"OperationNotSupported"}}]}

				event:complete
				data:

				""");
	}

	@Test
	void shouldWriteMultipleEventsForSubscription() throws Exception {
		GraphQlSseHandler handler = createSseHandler(SEARCH_DATA_FETCHER);
		MockHttpServletRequest request = createServletRequest("""
				{ "query": "subscription TestSubscription { bookSearch(author:\\\"Orwell\\\") { id name } }" }
				""");
		MockHttpServletResponse response = handleRequest(request, handler);

		assertThat(response.getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM_VALUE);
		assertThat(response.getContentAsString()).isEqualTo("""
				event:next
				data:{"data":{"bookSearch":{"id":"1","name":"Nineteen Eighty-Four"}}}

				event:next
				data:{"data":{"bookSearch":{"id":"5","name":"Animal Farm"}}}

				event:complete
				data:

				""");
	}

	@Test
	void shouldWriteEventsAndTerminalError() throws Exception {

		DataFetcher<?> errorDataFetcher = env -> Flux.just(BookSource.getBook(1L))
				.concatWith(Flux.error(new IllegalStateException("test error")));

		GraphQlSseHandler handler = createSseHandler(errorDataFetcher);
		MockHttpServletRequest request = createServletRequest("""
				{ "query": "subscription TestSubscription { bookSearch(author:\\\"Orwell\\\") { id name } }" }
				""");
		MockHttpServletResponse response = handleRequest(request, handler);

		assertThat(response.getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM_VALUE);
		assertThat(response.getContentAsString()).isEqualTo("""
				event:next
				data:{"data":{"bookSearch":{"id":"1","name":"Nineteen Eighty-Four"}}}

				event:next
				data:{"errors":[{"message":"Subscription error","locations":[],"extensions":{"classification":"INTERNAL_ERROR"}}]}

				event:complete
				data:

				""");
	}

	private GraphQlSseHandler createSseHandler(DataFetcher<?> dataFetcher) {
		return new GraphQlSseHandler(GraphQlSetup.schemaResource(BookSource.schema)
				.queryFetcher("bookById", (env) -> BookSource.getBookWithoutAuthor(1L))
				.subscriptionFetcher("bookSearch", dataFetcher)
				.toWebGraphQlHandler());
	}

	private MockHttpServletRequest createServletRequest(String query) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/");
		request.setContentType(MediaType.APPLICATION_JSON_VALUE);
		request.setContent(query.getBytes(StandardCharsets.UTF_8));
		request.addHeader("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);
		request.setAsyncSupported(true);
		return request;
	}

	private MockHttpServletResponse handleRequest(
			MockHttpServletRequest servletRequest, GraphQlSseHandler handler) throws ServletException, IOException {

		ServerRequest request = ServerRequest.create(servletRequest, MESSAGE_READERS);
		ServerResponse response = handler.handleRequest(request);
		if (response instanceof AsyncServerResponse asyncResponse) {
			asyncResponse.block();
		}

		MockHttpServletResponse servletResponse = new MockHttpServletResponse();
		response.writeTo(servletRequest, servletResponse, new DefaultContext());
		await().atMost(Duration.ofMillis(500)).until(() -> servletResponse.getContentAsString().contains("complete"));
		return servletResponse;
	}


	private static class DefaultContext implements ServerResponse.Context {

		@Override
		public List<HttpMessageConverter<?>> messageConverters() {
			return MESSAGE_READERS;
		}

	}

}
