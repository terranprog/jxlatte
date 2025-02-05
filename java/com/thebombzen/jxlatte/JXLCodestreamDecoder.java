package com.thebombzen.jxlatte;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.thebombzen.jxlatte.bundle.ExtraChannelType;
import com.thebombzen.jxlatte.bundle.ImageHeader;
import com.thebombzen.jxlatte.color.ColorEncodingBundle;
import com.thebombzen.jxlatte.color.ColorFlags;
import com.thebombzen.jxlatte.color.OpsinInverseMatrix;
import com.thebombzen.jxlatte.frame.BlendingInfo;
import com.thebombzen.jxlatte.frame.Frame;
import com.thebombzen.jxlatte.frame.FrameFlags;
import com.thebombzen.jxlatte.frame.FrameHeader;
import com.thebombzen.jxlatte.frame.features.Patch;
import com.thebombzen.jxlatte.io.Bitreader;
import com.thebombzen.jxlatte.io.Demuxer;
import com.thebombzen.jxlatte.io.Loggers;
import com.thebombzen.jxlatte.io.PushbackInputStream;
import com.thebombzen.jxlatte.util.FlowHelper;
import com.thebombzen.jxlatte.util.IntPoint;
import com.thebombzen.jxlatte.util.MathHelper;

public class JXLCodestreamDecoder {

    private float[][] transposeBuffer(float[][] src, int orientation) {
        IntPoint size = IntPoint.sizeOf(src);
        float[][] dest = orientation > 4 ? new float[size.x][size.y]
            : orientation > 1 ? new float[size.y][size.x] : null;
        switch (orientation) {
            case 1:
                return src;
            case 2:
                // flip horizontally
                for (IntPoint p : FlowHelper.range2D(size))
                    dest[p.y][size.x - 1 - p.x] = src[p.y][p.x];
                return dest;
            case 3:
                // rotate 180 degrees
                for (IntPoint p : FlowHelper.range2D(size))
                    dest[size.y - 1 - p.y][size.x - 1 - p.x] = src[p.y][p.x];
                return dest;
            case 4:
                // flip vertically
                for (IntPoint p : FlowHelper.range2D(size))
                    dest[size.y - 1 - p.y][p.x] = src[p.y][p.x];
                return dest;
            case 5:
                // transpose
                for (IntPoint p : FlowHelper.range2D(size))
                    dest[p.x][p.y] = src[p.y][p.x];
                return dest;
            case 6:
                // rotate clockwise
                for (IntPoint p : FlowHelper.range2D(size))
                    dest[p.x][size.y - 1 - p.y] = src[p.y][p.x];
                return dest;
            case 7:
                // skew transpose
                for (IntPoint p : FlowHelper.range2D(size))
                    dest[size.x - 1 - p.x][size.y - 1 - p.y] = src[p.y][p.x];
                return dest;
            case 8:
                // rotate counterclockwise
                for (IntPoint p : FlowHelper.range2D(size))
                    dest[size.x - 1 - p.x][p.y] = src[p.y][p.x];
                return dest;
            default:
                throw new IllegalStateException("Challenge complete how did we get here");
        }
    }

    private Bitreader bitreader;
    private PushbackInputStream in;
    private ImageHeader imageHeader;
    private JXLOptions options;
    private Demuxer demuxer;
    private FlowHelper flowHelper;

    public JXLCodestreamDecoder(PushbackInputStream in, JXLOptions options, Demuxer demuxer, FlowHelper flowHelper) {
        this.in = in;
        this.flowHelper = flowHelper;
        this.bitreader = new Bitreader(in);
        this.options = options;
        this.demuxer = demuxer;
    }

    private void computePatches(float[][][][] references, Frame frame) throws InvalidBitstreamException {
        FrameHeader header = frame.getFrameHeader();
        float[][][] frameBuffer = frame.getBuffer();
        int colorChannels = imageHeader.getColorChannelCount();
        int extraChannels = imageHeader.getExtraChannelCount();
        Patch[] patches = frame.getLFGlobal().patches;
        for (int i = 0; i < patches.length; i++) {
            Patch patch = patches[i];
            if (patch.ref > 3)
                throw new InvalidBitstreamException("Patch out of range");
            float[][][] refBuffer = references[patch.ref];
            // technically you can reference a nonexistent frame
            // you wouldn't but it's not against the rules
            if (refBuffer == null)
                continue;
            if (patch.height + patch.origin.y > refBuffer[0].length
                || patch.width + patch.origin.x > refBuffer[0][0].length)
                    throw new InvalidBitstreamException("Patch too large");
            for (int j = 0; j < patch.positions.length; j++) {
                int x0 = patch.positions[j].x;
                int y0 = patch.positions[j].y;
                if (x0 < 0 || y0 < 0)
                    throw new InvalidBitstreamException("Patch size out of bounds");
                if (patch.height + y0 > header.height
                    || patch.width + x0 > header.width)
                    throw new InvalidBitstreamException("Patch size out of bounds");
                for (int d = 0; d < colorChannels + extraChannels; d++) {
                    int c = d < colorChannels ? 0 : d - colorChannels + 1;
                    BlendingInfo info = patch.blendingInfos[j][c];
                    if (info.mode == 0)
                        continue;
                    boolean premult = imageHeader.hasAlpha()
                        ? imageHeader.getExtraChannelInfo(info.alphaChannel).alphaAssociated
                        : true;
                    boolean isAlpha = c > 0 &&
                        imageHeader.getExtraChannelInfo(c - 1).type == ExtraChannelType.ALPHA;
                    if (info.mode > 3 && header.upsampling > 1 && c > 0 &&
                            header.ecUpsampling[c - 1] << imageHeader.getExtraChannelInfo(c - 1).dimShift
                            != header.upsampling) {
                        throw new InvalidBitstreamException("Alpha channel upsampling mismatch during patches");
                    }
                    for (int y = 0; y < patch.height; y++) {
                        for (int x = 0; x < patch.width; x++) {
                            int oldX = x + x0;
                            int oldY = y + y0;
                            int newX = x + patch.origin.x;
                            int newY = y + patch.origin.y;
                            float oldSample = frameBuffer[d][oldY][oldX];
                            float newSample = refBuffer[d][newY][newX];
                            float alpha = 0f, newAlpha = 0f, oldAlpha = 0f;
                            if (info.mode > 3) {
                                oldAlpha = imageHeader.hasAlpha() ?
                                    frameBuffer[colorChannels + info.alphaChannel][oldY][oldX] : 1.0f;
                                newAlpha = imageHeader.hasAlpha() ?
                                    refBuffer[colorChannels + info.alphaChannel][newY][newX] : 1.0f;
                                if (info.clamp)
                                    newAlpha = MathHelper.clamp(newAlpha, 0.0f, 1.0f);
                                if (info.mode < 6 || !isAlpha || !premult)
                                    alpha = oldAlpha + newAlpha * (1 - oldAlpha);
                            }
                            float sample;
                            switch (info.mode) {
                                case 0:
                                    sample = oldSample;
                                    break;
                                case 1:
                                    sample = newSample;
                                    break;
                                case 2:
                                    sample = oldSample + newSample;
                                    break;
                                case 3:
                                    sample = oldSample * newSample;
                                    break;
                                case 4:
                                    sample = isAlpha ? alpha : premult ? newSample + oldSample * (1 - newAlpha)
                                        : (newSample * newAlpha + oldSample * oldAlpha * (1 - newAlpha)) / alpha;
                                    break;
                                case 5:
                                    sample = isAlpha ? alpha : premult ? oldSample + newSample * (1 - newAlpha)
                                        : (oldSample * newAlpha + newSample * oldAlpha * (1 - newAlpha)) / alpha;
                                    break;
                                case 6:
                                    sample = isAlpha ? newAlpha : oldSample + alpha * newSample;
                                    break;
                                case 7:
                                    sample = isAlpha ? oldAlpha : newSample + alpha * oldSample;
                                    break;
                                default:
                                    throw new IllegalStateException("Challenge complete how did we get here");
                            }
                            frameBuffer[d][oldY][oldX] = sample;
                        }
                    }
                }
            }
        }
    }

    public void performColorTransforms(OpsinInverseMatrix matrix, Frame frame) {
        float[][][] frameBuffer = frame.getBuffer();
        if (matrix != null)
            matrix.invertXYB(frameBuffer, imageHeader.getToneMapping().intensityTarget, flowHelper);

        if (frame.getFrameHeader().doYCbCr) {
            IntPoint size = frame.getPaddedFrameSize();
            for (int y = 0; y < size.y; y++) {
                for (int x = 0; x < size.x; x++) {
                    float cb = frameBuffer[0][y][x];
                    float yh = frameBuffer[1][y][x] + 0.50196078431372549019f;
                    float cr = frameBuffer[2][y][x];
                    frameBuffer[0][y][x] = yh + 1.402f * cr;
                    frameBuffer[1][y][x] = yh - 0.34413628620102214650f * cb - 0.71413628620102214650f * cr;
                    frameBuffer[2][y][x] = yh + 1.772f * cb;
                }
            }
        }
    }

    private static float getSample(float[][] arr, int x, int y) {
        if (y < 0 || y >= arr.length)
            return 0;
        if (x < 0 || x >= arr[y].length)
            return 0;
        return arr[y][x];
    }

    public void blendFrame(float[][][] canvas, float[][][][] reference, Frame frame)
            throws InvalidBitstreamException {
        int width = imageHeader.getSize().width;
        int height = imageHeader.getSize().height;
        FrameHeader header = frame.getFrameHeader();
        int frameXStart = Math.max(0, header.origin.x);
        int frameYStart = Math.max(0, header.origin.y);
        int frameXEnd = Math.min(width, header.width + header.origin.x);
        int frameYEnd = Math.min(height, header.height + header.origin.y);
        int frameColors = frame.getColorChannelCount();
        int imageColors = imageHeader.getColorChannelCount();
        for (int c = 0; c < canvas.length; c++) {
            int frameC = frameColors != imageColors ? (c == 0 ? 1 : c + 2) : c;
            float[][] newBuffer = frame.getBuffer()[frameC];
            BlendingInfo info;
            if (frameC < frameColors)
                info = frame.getFrameHeader().blendingInfo;
            else
                info = frame.getFrameHeader().ecBlendingInfo[frameC - frameColors];
            boolean isAlpha = c >= imageColors &&
                    imageHeader.getExtraChannelInfo(c - imageColors).type == ExtraChannelType.ALPHA;
            boolean premult = imageHeader.hasAlpha()
                        ? imageHeader.getExtraChannelInfo(info.alphaChannel).alphaAssociated
                        : true;
            float[][][] ref = reference[info.source];
            switch (info.mode) {
                case FrameFlags.BLEND_ADD:
                    if (ref != null) {
                        for (int y = frameYStart; y < frameYEnd; y++) {
                            for (int x = frameXStart; x < frameXEnd; x++) {
                                canvas[c][y][x] = frame.getSample(frameC, x, y) + getSample(ref[c], x, y);
                            }
                        }
                        break;
                    }
                // fall through here is intentional
                case FrameFlags.BLEND_REPLACE:
                    for (int y = frameYStart; y < frameYEnd; y++) {
                        System.arraycopy(newBuffer[y - frameYStart], 0, canvas[c][y],
                            frameXStart, frameXEnd - frameXStart);
                    }
                    break;
                case FrameFlags.BLEND_MULT:
                    for (int y = frameYStart; y < frameYEnd; y++) {
                        if (ref != null) {
                            for (int x = frameXStart; x < frameXEnd; x++) {
                                float newSample = frame.getSample(frameC, x, y);
                                if (info.clamp)
                                    newSample = MathHelper.clamp(newSample, 0.0f, 1.0f);
                                canvas[c][y][x] = newSample * getSample(ref[c], x, y);
                            }
                        } else {
                            Arrays.fill(canvas[c][y], frameXStart, frameXEnd, 0f);
                        }
                    }
                    break;
                case FrameFlags.BLEND_BLEND:
                    for (int y = frameYStart; y < frameYEnd; y++) {
                        for (int x = frameXStart; x < frameXEnd; x++) {
                            float oldAlpha = !imageHeader.hasAlpha() ? 1.0f : ref != null ?
                                getSample(ref[imageColors + info.alphaChannel], x, y) : 0.0f;
                            float newAlpha = !imageHeader.hasAlpha() ? 1.0f
                                : frame.getSample(frameColors + info.alphaChannel, x, y);
                            if (info.clamp)
                                newAlpha = MathHelper.clamp(newAlpha, 0.0f, 1.0f);
                            float alpha = 1.0f;
                            float oldSample = ref != null ? getSample(ref[c], x, y) : 0.0f;
                            float newSample = frame.getSample(frameC, x, y);
                            if (isAlpha || !premult)
                                alpha = oldAlpha + newAlpha * (1 - oldAlpha);
                            canvas[c][y][x] = isAlpha ? alpha : premult ? newSample + oldSample * (1 - newAlpha)
                            : (newSample * newAlpha + oldSample * oldAlpha * (1 - newAlpha)) / alpha;
                        }
                    }
                    break;
                case FrameFlags.BLEND_MULADD:
                    for (int y = frameYStart; y < frameYEnd; y++) {
                        for (int x = frameXStart; x < frameXEnd; x++) {
                            float oldAlpha = !imageHeader.hasAlpha() ? 1.0f : ref != null ?
                                getSample(ref[imageColors + info.alphaChannel], x, y) : 0.0f;
                            float newAlpha = !imageHeader.hasAlpha() ? 1.0f
                                : frame.getSample(frameColors + info.alphaChannel, x, y);
                            if (info.clamp)
                                newAlpha = MathHelper.clamp(newAlpha, 0.0f, 1.0f);
                            float oldSample = ref != null ? getSample(ref[c], x, y) : 0.0f;
                            float newSample = frame.getSample(frameC, x, y);
                            canvas[c][y][x] = isAlpha ? oldAlpha : oldSample + newAlpha * newSample;
                        }
                    }
                    break;
                default:
                    throw new InvalidBitstreamException("Illegal blend mode");
            }
        }
    }

    public JXLImage decode() throws IOException {
        return decode(new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8)));
    }

    public JXLImage decode(PrintWriter err) throws IOException {
        if (bitreader.atEnd())
            return null;
        Loggers loggers = new Loggers(options, err);
        bitreader.showBits(16); // force the level to be populated
        int level = demuxer.getLevel();
        this.imageHeader = ImageHeader.parse(bitreader, level);
        loggers.log(Loggers.LOG_INFO, "Image: %s", options.input != null ? options.input : "<stdin>");
        loggers.log(Loggers.LOG_INFO, "    Level: %d", level);
        loggers.log(Loggers.LOG_INFO, "    Size: %dx%d", imageHeader.getSize().width, imageHeader.getSize().height);
        boolean gray = imageHeader.getColorChannelCount() < 3;
        boolean alpha = imageHeader.hasAlpha();
        loggers.log(Loggers.LOG_INFO, "    Pixel Format: %s",
            gray ? (alpha ? "Gray + Alpha" : "Grayscale") : (alpha ? "RGBA" : "RGB"));
        loggers.log(Loggers.LOG_INFO, "    Bit Depth: %d", imageHeader.getBitDepthHeader().bitsPerSample);
        loggers.log(Loggers.LOG_VERBOSE, "    Extra Channels: %d", imageHeader.getExtraChannelCount());
        loggers.log(Loggers.LOG_VERBOSE, "    XYB Encoded: %b", imageHeader.isXYBEncoded());
        ColorEncodingBundle ce = imageHeader.getColorEncoding();
        if (!gray)
            loggers.log(Loggers.LOG_VERBOSE, "    Primaries: %s", ColorFlags.primariesToString(ce.primaries));
        loggers.log(Loggers.LOG_VERBOSE, "    White Point: %s", ColorFlags.whitePointToString(ce.whitePoint));
        loggers.log(Loggers.LOG_VERBOSE, "    Transfer Function: %s", ColorFlags.transferToString(ce.tf));
        if (imageHeader.getAnimationHeader() != null)
            loggers.log(Loggers.LOG_INFO, "    Animated: true");

        if (imageHeader.getPreviewHeader() != null) {
            Frame frame = new Frame(bitreader, imageHeader, flowHelper, loggers);
            frame.readHeader();
            frame.skipFrameData();
        }

        List<Frame> frames = new ArrayList<>();
        float[][][][] reference = new float[4][][][];
        FrameHeader header;
        // last one is always null, avoids ugly branch later
        float[][][][] lfBuffer = new float[5][][][];

        do {
            Frame frame = new Frame(bitreader, imageHeader, flowHelper, loggers);
            frame.readHeader();
            header = frame.getFrameHeader();
            if (frames.size() == 0) {
                loggers.log(Loggers.LOG_INFO, "    Lossless: %s",
                    header.encoding == FrameFlags.VARDCT || imageHeader.isXYBEncoded() ? "No" : "Possibly");
            }
            frame.printDebugInfo();
            loggers.log(Loggers.LOG_TRACE, "%s", header);
            if (lfBuffer[header.lfLevel] == null && (header.flags & FrameFlags.USE_LF_FRAME) != 0)
                throw new InvalidBitstreamException("LF Level too large");
            frame.decodeFrame(lfBuffer[header.lfLevel]);
            if (header.lfLevel > 0)
                lfBuffer[header.lfLevel - 1] = frame.getBuffer();
            frames.add(frame);
        } while (!header.isLast);

        OpsinInverseMatrix matrix = null;
        if (imageHeader.isXYBEncoded()) {
            ColorEncodingBundle bundle = imageHeader.getColorEncoding();
            matrix = imageHeader.getOpsinInverseMatrix().getMatrix(bundle.prim, bundle.white);
        }

        float[][][] canvas = new float[imageHeader.getColorChannelCount() + imageHeader.getExtraChannelCount()]
            [imageHeader.getSize().height][imageHeader.getSize().width];

        long invisibleFrames = 0;
        long visibleFrames = 0;

        for (Frame frame : frames) {
            header = frame.getFrameHeader();
            boolean save = (header.saveAsReference != 0 || header.duration == 0)
                && !header.isLast && header.type != FrameFlags.LF_FRAME;
            if (frame.isVisible()) {
                visibleFrames++;
                invisibleFrames = 0;
            } else {
                invisibleFrames++;
            }
            frame.initializeNoise((visibleFrames << 32) | invisibleFrames);
            frame.upsample();
            if (save && header.saveBeforeCT)
                reference[header.saveAsReference] = MathHelper.deepCopyOf(frame.getBuffer());
            computePatches(reference, frame);
            frame.renderSplines();
            frame.synthesizeNoise();
            performColorTransforms(matrix, frame);
            if (header.encoding == FrameFlags.VARDCT && options.renderVarblocks)
                frame.drawVarblocks();
            if (header.type == FrameFlags.REGULAR_FRAME || header.type == FrameFlags.SKIP_PROGRESSIVE)
                blendFrame(canvas, reference, frame);
            if (save && !header.saveBeforeCT)
                reference[header.saveAsReference] = MathHelper.deepCopyOf(canvas);
        }

        int orientation = imageHeader.getOrientation();

        float[][][] orientedCanvas = new float[canvas.length][][];

        for (int i = 0; i < orientedCanvas.length; i++)
            orientedCanvas[i] = transposeBuffer(canvas[i], orientation);

        JXLImage image = new JXLImage(orientedCanvas, imageHeader, flowHelper);

        bitreader.zeroPadToByte();
        byte[] drain = bitreader.drainCache();
        if (drain != null)
            demuxer.pushBack(drain);
        while ((drain = in.drain()) != null)
            demuxer.pushBack(drain);

        return image;
    }
}
