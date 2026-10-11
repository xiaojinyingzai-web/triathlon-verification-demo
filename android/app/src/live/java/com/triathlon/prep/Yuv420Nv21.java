package com.triathlon.prep;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Reads borrowed Y/U/V planes synchronously. Returned NV21 is owned by the caller. No JPEG path. */
final class Yuv420Nv21 {
    static final class Plane {
        final ByteBuffer buffer;
        final int rowStride, pixelStride;
        Plane(ByteBuffer buffer, int rowStride, int pixelStride) {
            if (buffer == null) throw new IllegalArgumentException("Missing plane");
            this.buffer = buffer.duplicate(); this.rowStride = rowStride; this.pixelStride = pixelStride;
        }
        void validate(int columns, int rows) {
            long rowBytes = (long)(columns - 1) * pixelStride + 1;
            long last = (long)(rows - 1) * rowStride + rowBytes;
            if (pixelStride < 1 || rowStride < 1 || rowBytes > rowStride || last > buffer.remaining())
                throw new IllegalArgumentException("Truncated/overlapping YUV plane");
        }
        byte sample(int row, int column) { return buffer.get(buffer.position() + row * rowStride + column * pixelStride); }
    }
    static byte[] convert(int width, int height, int left, int top, int right, int bottom, Plane[] planes) {
        if (width < 2 || height < 2 || (width & 1) != 0 || (height & 1) != 0
                || (long)width * height > 4096L * 4096 || left != 0 || top != 0 || right != width || bottom != height
                || planes == null || planes.length != 3 || planes[0] == null || planes[1] == null || planes[2] == null)
            throw new IllegalArgumentException("Even, full-crop three-plane YUV required");
        planes[0].validate(width, height); planes[1].validate(width / 2, height / 2); planes[2].validate(width / 2, height / 2);
        byte[] out = new byte[width * height * 3 / 2];
        try {
            int n = 0;
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) out[n++] = planes[0].sample(y, x);
            for (int y = 0; y < height / 2; y++) for (int x = 0; x < width / 2; x++) {
                out[n++] = planes[2].sample(y, x); // NV21 requires V then U, regardless of source interleaving.
                out[n++] = planes[1].sample(y, x);
            }
            return out;
        } catch (RuntimeException ex) { Arrays.fill(out, (byte)0); throw ex; }
    }
}
