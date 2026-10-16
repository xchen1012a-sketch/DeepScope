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
package io.agentscope.advisor.knowledge;

import java.awt.geom.Point2D;
import java.io.IOException;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

/** Inspect actual drawing operators, including inline images and nested forms. */
final class PdfPageGraphics extends PDFGraphicsStreamEngine {
    boolean image;
    int paths;
    private Point2D current = new Point2D.Float();

    PdfPageGraphics(PDPage page) throws IOException {
        super(page);
        processPage(page);
    }

    @Override
    public void drawImage(PDImage image) {
        this.image = true;
    }

    @Override
    public void appendRectangle(Point2D a, Point2D b, Point2D c, Point2D d) {
        current = a;
    }

    @Override
    public void clip(int windingRule) {}

    @Override
    public void moveTo(float x, float y) {
        current = new Point2D.Float(x, y);
    }

    @Override
    public void lineTo(float x, float y) {
        current = new Point2D.Float(x, y);
    }

    @Override
    public void curveTo(float a, float b, float c, float d, float x, float y) {
        current = new Point2D.Float(x, y);
    }

    @Override
    public Point2D getCurrentPoint() {
        return current;
    }

    @Override
    public void closePath() {}

    @Override
    public void endPath() {}

    @Override
    public void strokePath() {
        paths++;
    }

    @Override
    public void fillPath(int windingRule) {
        paths++;
    }

    @Override
    public void fillAndStrokePath(int windingRule) {
        paths++;
    }

    @Override
    public void shadingFill(COSName name) {
        paths++;
    }
}
