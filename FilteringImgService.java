
class FilteringImgService{



    private static byte toChannel(double v) {
        long rounded = Math.round(v);              
        if (rounded < 0) return 0;                 
        if (rounded > 255) return (byte) 255;
        return (byte) rounded;
    }
    

    public static byte[] grayscaleFilter(int[] pixels, byte[] grayPixels, int w, TilePosition position){
        for (int y = position.yStart(); y < position.yEnd(); y++){
            for (int x = position.xStart(); x < position.xEnd(); x++) {
                int rgb = pixels[y * w + x];
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >>  8) & 0xFF;
                int b =  rgb & 0xFF;

                double gray = 0.299 * r + 0.587 * g + 0.114 * b;
                grayPixels[y * w + x] = toChannel(gray);
            }
        }
        return grayPixels;
    }

    public static byte[] sobelFilter(byte[] grayPixels, byte[] sobelPixels, int w, int h, TilePosition position){
        for (int y = position.yStart(); y < position.yEnd(); y++){
            int yPrev = Math.max(0, y - 1);
            int yNext = Math.min(h - 1, y + 1);
            for (int x = position.xStart(); x < position.xEnd(); x++){
                int xPrev = Math.max(0, x - 1);
                int xNext = Math.min(w - 1, x + 1);

                double gx = -(grayPixels[yPrev * w + xPrev] & 0xFF) + (grayPixels[yPrev * w + xNext] & 0xFF) 
                            - 2.0 * (grayPixels[y * w + xPrev] & 0xFF) + 2.0 * (grayPixels[y * w + xNext] & 0xFF) 
                            - (grayPixels[yNext * w + xPrev] & 0xFF) + (grayPixels[yNext * w + xNext] & 0xFF);
                double gy = -(grayPixels[yPrev * w + xPrev] & 0xFF) - 2.0 * (grayPixels[yPrev * w + x] & 0xFF) 
                            - (grayPixels[yPrev * w + xNext] & 0xFF) + (grayPixels[yNext * w + xPrev] & 0xFF) 
                            + 2.0 * (grayPixels[yNext * w + x] & 0xFF) + (grayPixels[yNext * w + xNext] & 0xFF);
                double g = Math.sqrt(gx * gx + gy * gy);

                sobelPixels[y * w + x] = toChannel(g);
            }
        }
        return sobelPixels;
    }


    public static void yCbCrFilter(int[] pixels, byte[] yPix, byte[] cbPix, byte[] crPix, int w, TilePosition position){
        for (int y = position.yStart(); y < position.yEnd(); y++){
            for (int x = position.xStart(); x < position.xEnd(); x++) {
                int rgb = pixels[y * w + x];
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >>  8) & 0xFF;
                int b =  rgb & 0xFF;
                
                double yp = 0.299 * r + 0.587 * g + 0.114 * b;
                double cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b;
                double cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b;

                yPix[y * w + x] = toChannel(yp);
                cbPix[y * w + x] = toChannel(cb);
                crPix[y * w + x] = toChannel(cr);            
            }
        }
    }

    public static int[] yCbCrToRgbFilter(byte[] yPix, byte[] cbPix, byte[] crPix, int[] roundtripPixels, int w, TilePosition position){
        for (int y = position.yStart(); y < position.yEnd(); y++){
            for (int x = position.xStart(); x < position.xEnd(); x++){
                int idx = y * w + x;
                int yp = yPix[idx] & 0xFF;
                int cb = cbPix[idx] & 0xFF;
                int cr = crPix[idx] & 0xFF;

                int r = toChannel(yp + 1.402 * (cr - 128)) & 0xFF;
                int g = toChannel(yp - 0.344136 * (cb - 128) - 0.714136 * (cr - 128)) & 0xFF;
                int b = toChannel(yp + 1.772 * (cb - 128)) & 0xFF;

                roundtripPixels[idx] = (r << 16) | (g << 8) | b;
            }
        }
        return roundtripPixels;
    }

    public static int[] barrelDistortionFilter(int[] pixels, int[] distortPixels, int w, int h, double k, TilePosition position){
        double cx = (w - 1) / 2.0;
        double cy = (h - 1) / 2.0;
        double maxR = Math.sqrt(cx * cx + cy * cy);

        for (int y = position.yStart(); y < position.yEnd(); y++){
            for (int x = position.xStart(); x < position.xEnd(); x++){
                double dxCenter = x - cx;
                double dyCenter = y - cy;
                double r = Math.sqrt(dxCenter * dxCenter + dyCenter * dyCenter) / maxR;
                double factor = 1.0 + k * r * r;

                double xs = cx + dxCenter * factor;
                double ys = cy + dyCenter * factor;

                if (xs < 0.0 || xs > w - 1 || ys < 0.0 || ys > h - 1){
                    distortPixels[y * w + x] = 0;
                    continue;
                }

                int x0 = (int) Math.floor(xs);
                int y0 = (int) Math.floor(ys);
                int x1 = Math.min(x0 + 1, w - 1);
                int y1 = Math.min(y0 + 1, h - 1);

                double tx = xs - x0;
                double ty = ys - y0;

                double w00 = (1.0 - tx) * (1.0 - ty);
                double w10 = tx * (1.0 - ty);
                double w01 = (1.0 - tx) * ty;
                double w11 = tx * ty;

                int p00 = pixels[y0 * w + x0];
                int p10 = pixels[y0 * w + x1];
                int p01 = pixels[y1 * w + x0];
                int p11 = pixels[y1 * w + x1];

                double red = w00 * ((p00 >> 16) & 0xFF) + w10 * ((p10 >> 16) & 0xFF)
                           + w01 * ((p01 >> 16) & 0xFF) + w11 * ((p11 >> 16) & 0xFF);
                double green = w00 * ((p00 >> 8) & 0xFF) + w10 * ((p10 >> 8) & 0xFF)
                             + w01 * ((p01 >> 8) & 0xFF) + w11 * ((p11 >> 8) & 0xFF);
                double blue = w00 * (p00 & 0xFF) + w10 * (p10 & 0xFF)
                            + w01 * (p01 & 0xFF) + w11 * (p11 & 0xFF);

                int rOut = toChannel(red) & 0xFF;
                int gOut = toChannel(green) & 0xFF;
                int bOut = toChannel(blue) & 0xFF;

                distortPixels[y * w + x] = (rOut << 16) | (gOut << 8) | bOut;
            }
        }
        return distortPixels;
    }

} 
