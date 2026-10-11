package com.triathlon.prep.cefk.hardware;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.android.print.sdk.PrinterInstance;
import com.csht.cshtreadcard_super.PrintUtils;

/** USB only. PID/VID, effective dots and charset must come from the delivered printer configuration. */
public final class CshtPrinterPort implements CefkPrinter.Port {
    private final Context context;
    private final int productId, vendorId;
    private final PrintUtils utils;
    private boolean closed;
    public CshtPrinterPort(Context context, int productId, int vendorId) {
        if (context == null || productId < 1 || productId > 65535 || vendorId < 1 || vendorId > 65535)
            throw new IllegalArgumentException("Explicit USB PID/VID required");
        this.context = context.getApplicationContext(); this.productId = productId; this.vendorId = vendorId;
        // A separate PrintUtils instance avoids the manufacturer's shared singleton printer slot.
        this.utils = new PrintUtils();
    }
    @Override public void open() {
        if (closed) throw new IllegalStateException("Port closed");
        // Manufacturer callback true means openConnection() was called; not necessarily connected.
        utils.openConnection(productId, vendorId, context, (opened, ignoredMessage) -> {});
    }
    @Override public boolean connected() {
        PrinterInstance printer = utils.getPrinter();
        return !closed && printer != null && printer.isConnected();
    }
    private PrinterInstance printer() {
        if (!connected()) throw new IllegalStateException("Printer not connected");
        return utils.getPrinter();
    }
    @Override public int text(String text, String encoding) {
        PrinterInstance printer = printer();
        printer.setEncoding(encoding);
        return printer.printText(text);
    }
    @Override public int image(byte[] png, int dotWidth) {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
        if (bounds.outWidth != dotWidth || bounds.outHeight < 1 || bounds.outHeight > 8192
                || (long) bounds.outWidth * bounds.outHeight > 4_000_000
                || !"image/png".equals(bounds.outMimeType))
            throw new IllegalArgumentException("PNG must match configured dots and bounded height");
        Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (bitmap == null) throw new IllegalArgumentException("Invalid PNG");
        try { return printer().printImage(bitmap); }
        finally { bitmap.recycle(); }
    }
    @Override public void close() {
        if (closed) return;
        closed = true; utils.closeConnection();
    }
}
