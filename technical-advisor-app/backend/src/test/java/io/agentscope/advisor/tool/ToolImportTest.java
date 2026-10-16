/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.advisor.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** OpenAPI fragments become one tool per operation; a JSON Schema becomes one tool. */
class ToolImportTest {

    private final ToolImport importer = new ToolImport(new ObjectMapper());

    @Test
    void openApiFragmentExpandsPathQueryAndBody() {
        String fragment =
                """
                {
                  "servers": [{"url": "https://example.com/api"}],
                  "paths": {
                    "/users/{id}": {
                      "get": {
                        "operationId": "getUser",
                        "summary": "Fetch a user",
                        "parameters": [
                          {"name": "id", "in": "path", "required": true, "schema": {"type": "string"}},
                          {"name": "verbose", "in": "query", "schema": {"type": "boolean"}}
                        ]
                      },
                      "post": {
                        "operationId": "createUser",
                        "summary": "Create a user",
                        "requestBody": {
                          "required": true,
                          "content": {
                            "application/json": {
                              "schema": {
                                "type": "object",
                                "required": ["name"],
                                "properties": {"name": {"type": "string", "description": "Display name"}}
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;

        var tools = importer.parseOpenApi(fragment, null);

        assertEquals(2, tools.size());
        var getUser = tools.get(0);
        assertEquals("getUser", getUser.name());
        assertEquals("http", getUser.origin());
        assertEquals("GET", getUser.httpMethod());
        assertEquals("https://example.com/api/users/{id}", getUser.httpUrl());
        assertTrue(getUser.inputSchema().contains("\"id\""));
        assertTrue(getUser.inputSchema().contains("\"verbose\""));
        assertTrue(getUser.inputSchema().contains("\"required\""));
        var createUser = tools.get(1);
        assertEquals("createUser", createUser.name());
        assertEquals("POST", createUser.httpMethod());
        assertTrue(createUser.inputSchema().contains("\"name\""));
        assertTrue(createUser.inputSchema().contains("Display name"));
    }

    @Test
    void blankServerAddressStaysDeclared() {
        String fragment =
                """
                {"paths": {"/ping": {"get": {"operationId": "ping", "summary": "Ping"}}}}
                """;

        var tools = importer.parseOpenApi(fragment, "  ");

        assertEquals(1, tools.size());
        assertEquals("declared", tools.get(0).origin());
        assertEquals(null, tools.get(0).httpUrl());
    }

    @Test
    void callerAddressOverridesTheDocumentServer() {
        String fragment =
                """
                {
                  "servers": [{"url": "https://ignored.example"}],
                  "paths": {"/ping": {"get": {"operationId": "ping", "summary": "Ping"}}}
                }
                """;

        var tools = importer.parseOpenApi(fragment, "https://override.example/");

        assertEquals("https://override.example/ping", tools.get(0).httpUrl());
        assertEquals("http", tools.get(0).origin());
    }

    @Test
    void jsonSchemaBecomesOneTool() {
        String schema =
                """
                {
                  "name": "lookup",
                  "description": "Look up a record",
                  "inputSchema": {
                    "type": "object",
                    "required": ["q"],
                    "properties": {"q": {"type": "string"}}
                  }
                }
                """;

        var tools = importer.parseSchema(schema, "https://records.example/search");

        assertEquals(1, tools.size());
        assertEquals("lookup", tools.get(0).name());
        assertEquals("Look up a record", tools.get(0).description());
        assertEquals("http", tools.get(0).origin());
        assertEquals("POST", tools.get(0).httpMethod());
        assertTrue(tools.get(0).inputSchema().contains("\"q\""));
    }

    @Test
    void recognizesOperationServerWithoutManualAddress() {
        var parsed =
                importer.parseOpenApi(
                        """
                        {"servers":[{"url":"https://root.example"}],"paths":{"/lookup":{"get":{"operationId":"lookup","servers":[{"url":"https://operation.example/v2"}]}}}}
                        """,
                        null);
        assertEquals("https://operation.example/v2/lookup", parsed.get(0).httpUrl());
    }

    @Test
    void expandsLocalReferencesAndPathParameters() {
        var parsed =
                importer.parseOpenApi(
                        """
                        {"servers":[{"url":"https://example.test"}],"components":{"parameters":{"id":{"name":"id","in":"path","required":true,"schema":{"type":"integer"}}}},"paths":{"/items/{id}":{"parameters":[{"$ref":"#/components/parameters/id"}],"get":{"operationId":"readItem"}}}}
                        """,
                        null);
        assertTrue(parsed.get(0).inputSchema().contains("\"id\""));
        assertTrue(parsed.get(0).inputSchema().contains("\"integer\""));
    }

    @Test
    void acceptsYamlAndServerVariables() {
        var parsed =
                importer.parseOpenApi(
                        """
                        openapi: 3.0.3
                        servers:
                          - url: https://{environment}.example.test
                            variables:
                              environment:
                                default: api
                        paths:
                          /lookup:
                            get:
                              operationId: lookup
                        """,
                        null);
        assertEquals("https://api.example.test/lookup", parsed.get(0).httpUrl());
    }

    @Test
    void rejectsEmptyOperationsAndUnsupportedReferencesInsteadOfImportingNothing() {
        assertEquals(
                "no_operations",
                assertThrows(ToolProblem.class, () -> importer.parseOpenApi("{\"paths\":{}}", null))
                        .code());
        assertEquals(
                "unsupported_reference",
                assertThrows(
                                ToolProblem.class,
                                () ->
                                        importer.parseOpenApi(
                                                """
                                                {"paths":{"/lookup":{"get":{"parameters":[{"$ref":"https://example.test/private.json"}]}}}}
                                                """,
                                                null))
                        .code());
    }

    @Test
    void normalizesCommonToolNamesInsteadOfRejectingThem() {
        var parsed =
                importer.parseSchema(
                        """
                        {"title":"Weather API","inputSchema":{"type":"object"}}
                        """,
                        "https://weather.example/now");

        assertEquals("Weather_API", parsed.get(0).name());
    }

    @Test
    void normalizesOperationIdsAndDerivedNamesWithoutLosingUniqueness() {
        var dotted =
                importer.parseOpenApi(
                        """
                        {"servers":[{"url":"https://example.test"}],"paths":{"/users":{"get":{"operationId":"users.list"}}}}
                        """,
                        null);
        assertEquals("users_list", dotted.get(0).name());

        var longPath =
                importer.parseOpenApi(
                        """
                        {"servers":[{"url":"https://example.test"}],"paths":{"/api/v1/organizations/{orgId}/projects/{projectId}/members/{memberId}/roles":{"get":{}}}}
                        """,
                        null);
        assertEquals(64, longPath.get(0).name().length());
        assertTrue(longPath.get(0).name().startsWith("get_api_v1_organizations"));
    }

    @Test
    void aNameWithNothingUsableIsRejectedWithACode() {
        assertEquals(
                "invalid_name",
                assertThrows(
                                ToolProblem.class,
                                () ->
                                        importer.parseSchema(
                                                "{\"title\":\"***\",\"inputSchema\":{\"type\":\"object\"}}",
                                                "https://example.test"))
                        .code());
    }
}
