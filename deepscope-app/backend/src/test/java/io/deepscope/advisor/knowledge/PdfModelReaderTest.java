/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.deepscope.advisor.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deepscope.advisor.model.ChatModelSource;
import io.deepscope.core.message.ImageBlock;
import io.deepscope.core.message.TextBlock;
import io.deepscope.core.model.ChatResponse;
import io.deepscope.core.model.Model;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class PdfModelReaderTest {
    @Test
    void mixedLocationAndMissingContentWarningsStillBlockPublication() throws Exception {
        var result =
                reader(
                                "{\"blocks\":[{\"type\":\"paragraph\",\"text\":\"部分正文\",\"interpretation\":false}],\"warnings\":[\"坐标为估算，部分文字无法识别\"]}")
                        .parse(new byte[0], page);
        assertEquals("review", result.status());
    }

    @Test
    void invalidFormatGetsExactlyOneCorrectionFromTheSameImage() throws Exception {
        var reader = reader("not json");
        when(model.stream(anyList(), anyList(), isNull()))
                .thenReturn(
                        Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("not json")
                                                                .build()))
                                        .build()),
                        Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text(
                                                                        "```json\n"
                                                                            + "{\"blank\":true,\"blocks\":[],\"warnings\":[]}\n"
                                                                            + "```")
                                                                .build()))
                                        .build()));
        assertEquals("blank", reader.parse(new byte[] {1, 2}, page).status());
        verify(model, times(2)).stream(anyList(), anyList(), isNull());
    }

    @Test
    void networkErrorsDoNotTriggerFormatCorrection() {
        var reader = reader("unused");
        when(model.stream(anyList(), anyList(), isNull()))
                .thenReturn(Flux.error(new java.io.IOException("private upstream detail")));
        assertThrows(Exception.class, () -> reader.parse(new byte[0], page));
        verify(model).stream(anyList(), anyList(), isNull());
    }

    @Test
    void inconsistentTableRowsFailAfterOnlyOneCorrection() {
        var reader =
                reader(
                        "{\"blocks\":[{\"type\":\"table\",\"table\":{\"columns\":[\"A\",\"B\"],\"rows\":[[\"12\"]]},\"interpretation\":false}],\"warnings\":[]}");
        assertThrows(IllegalArgumentException.class, () -> reader.parse(new byte[0], page));
        verify(model, times(2)).stream(anyList(), anyList(), isNull());
    }

    @Test
    void positioningNotesDoNotFailAnOtherwiseCompletePage() throws Exception {
        var result =
                reader(
                                "{\"complete\":true,\"blank\":false,\"blocks\":[{\"type\":\"paragraph\",\"text\":\"完整正文\",\"interpretation\":false}],\"warnings\":[\"box"
                                    + " 坐标为基于页面布局的估算值，可能与实际渲染位置存在偏差。\"],\"issues\":[]}")
                        .parse(new byte[0], page);
        assertEquals("ready", result.status());
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    void explicitContentFailureCannotBeOverriddenByCompleteFlag() throws Exception {
        var result =
                reader(
                                "{\"complete\":true,\"blocks\":[{\"type\":\"paragraph\",\"text\":\"部分正文\",\"interpretation\":false}],\"warnings\":[],\"issues\":[{\"code\":\"content_missing\",\"severity\":\"error\",\"message\":\"下半页缺失\"}]}")
                        .parse(new byte[0], page);
        assertEquals("review", result.status());
    }

    @Test
    void invalidLocationDropsOnlyLocationNotValidText() throws Exception {
        var result =
                reader(
                                "{\"complete\":true,\"blocks\":[{\"type\":\"paragraph\",\"text\":\"金额"
                                    + " 123.45\",\"interpretation\":false,\"box\":[0.9,0.1,0.5,0.2]}],\"warnings\":[]}")
                        .parse(new byte[0], page);
        assertEquals("ready", result.status());
        assertEquals(null, result.blocks().get(0).box());
        assertEquals("金额 123.45", result.blocks().get(0).text());
    }

    @Test
    void structuredTablesKeepEmptyCellsAndEscapePipes() throws Exception {
        var result =
                reader(
                                "{\"complete\":true,\"blocks\":[{\"type\":\"table\",\"table\":{\"columns\":[\"项目\",\"金额\"],\"rows\":[[\"A|B\",\"12.50\"],[\"C\",null]]},\"interpretation\":false}],\"warnings\":[]}")
                        .parse(new byte[0], page);
        assertTrue(result.blocks().get(0).text().contains("A\\|B"));
        assertTrue(result.blocks().get(0).text().contains("12.50"));
        assertTrue(result.blocks().get(0).text().contains("| C |  |"));
    }

    private final Model model = mock(Model.class);
    private final ChatModelSource models = mock(ChatModelSource.class);
    private final PdfContent.Page page =
            new PdfContent.Page(2, "iv", 600, 800, "failed", "native", "", List.of(), List.of());

    private PdfModelReader reader(String result) {
        when(models.forEndpoint("vision")).thenReturn(Optional.of(model));
        when(models.forEndpoint("rank")).thenReturn(Optional.of(model));
        when(model.stream(anyList(), anyList(), isNull()))
                .thenReturn(
                        Flux.just(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text(result).build()))
                                        .build()));
        return new PdfModelReader(models, new ObjectMapper(), "vision", "rank");
    }

    @Test
    void sendsImageToExplicitEndpointAndPreservesModelProvenance() throws Exception {
        var result =
                reader(
                                "{\"blank\":false,\"blocks\":[{\"type\":\"table\",\"text\":\"|A|USD|\\n"
                                    + "|--|--|\\n"
                                    + "|x|12|\",\"box\":[0.1,0.2,0.5,0.3],\"interpretation\":false}],\"warnings\":[]}")
                        .parse(new byte[] {1, 2, 3}, page);
        assertEquals(2, result.pageNumber());
        assertEquals("iv", result.pageLabel());
        assertEquals("vision", result.method());
        assertEquals("table", result.blocks().get(0).type());
        assertFalse(result.warnings().isEmpty());
        verify(model).stream(
                argThat(
                        messages ->
                                messages.get(0).getContent().stream()
                                        .anyMatch(ImageBlock.class::isInstance)),
                anyList(),
                isNull());
        verify(models, never()).current();
    }

    @Test
    void doesNotFallbackToCurrentChatModel() {
        var reader = new PdfModelReader(models, new ObjectMapper(), "", "");
        assertFalse(reader.available());
        assertThrows(IllegalStateException.class, () -> reader.parse(new byte[0], page));
        verifyNoInteractions(models);
    }

    @Test
    void malformedJsonAndMissingEvidenceAreRejected() {
        for (String reply : List.of("not json", "{}", "{\"blocks\":[],\"warnings\":[]}")) {
            assertThrows(Exception.class, () -> reader(reply).parse(new byte[0], page));
        }
    }

    @Test
    void cannotDisguiseFigureInterpretationAsTranscription() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        reader(
                                        "{\"blocks\":[{\"type\":\"image\",\"text\":\"trend\",\"interpretation\":false}],\"warnings\":[]}")
                                .parse(new byte[0], page));
    }

    @Test
    void uncertainRecognitionRequiresReview() throws Exception {
        assertEquals(
                "review",
                reader(
                                "{\"blocks\":[{\"type\":\"paragraph\",\"text\":\"无法辨认金额\",\"interpretation\":false}],\"warnings\":[\"金额模糊\"]}")
                        .parse(new byte[0], page)
                        .status());
    }

    @Test
    void uncertainBlankPageCannotBeCountedAsSuccessfullyEmpty() throws Exception {
        assertEquals(
                "review",
                reader("{\"blank\":true,\"blocks\":[],\"warnings\":[\"正文无法辨认\"]}")
                        .parse(new byte[0], page)
                        .status());
        var nativePage =
                new PdfContent.Page(
                        1,
                        null,
                        600,
                        800,
                        "ready",
                        "native",
                        "Known evidence",
                        List.of(),
                        List.of());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        reader("{\"blank\":true,\"blocks\":[],\"warnings\":[]}")
                                .parse(new byte[0], nativePage));
    }

    @Test
    void oversizedAndTruncatedModelResponsesFail() {
        assertThrows(
                IllegalArgumentException.class,
                () -> reader("x".repeat(262145)).parse(new byte[0], page));
        var reader = reader("{\"blank\":true,\"blocks\":[],\"warnings\":[]}");
        when(model.stream(anyList(), anyList(), isNull()))
                .thenReturn(
                        Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text(
                                                                        "{\"blank\":true,\"blocks\":[],\"warnings\":[]}")
                                                                .build()))
                                        .finishReason("length")
                                        .build()));
        assertThrows(IllegalArgumentException.class, () -> reader.parse(new byte[0], page));
    }

    @Test
    void rerankerMustReturnAnExactPermutation() throws Exception {
        assertEquals(List.of("1", "0"), reader("[1,0]").rerank("question", List.of("a", "b")));
        for (String invalid : List.of("[0,0]", "[0]", "[0,2]", "[0,1.2]", "[-1,0]")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> reader(invalid).rerank("q", List.of("a", "b")));
        }
    }
}
