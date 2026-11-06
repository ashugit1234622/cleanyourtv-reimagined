package com.gathin.tvhome.bridge.qr;

import android.graphics.Bitmap;

public final class QrBitmapHelper {
    private QrBitmapHelper() {}

    public static Bitmap createQrBitmap(String text, int scale, int border) {
        if (text == null || text.isEmpty()) return null;
        try {
            QrCode qr = QrCode.encodeText(text, QrCode.Ecc.LOW);
            int size = qr.size;
            int dim = (size + border * 2) * scale;
            Bitmap bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[dim * dim];
            for (int y = 0; y < dim; y++) {
                for (int x = 0; x < dim; x++) {
                    int qrx = x / scale - border;
                    int qry = y / scale - border;
                    boolean dark = qrx >= 0 && qrx < size && qry >= 0 && qry < size && qr.getModule(qrx, qry);
                    pixels[y * dim + x] = dark ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            bmp.setPixels(pixels, 0, dim, 0, 0, dim, dim);
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }
}

