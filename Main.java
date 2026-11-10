import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import threadPool.CustomThreadPool;
import threadPool.Task;
import threadPool.TaskType;

public class Main {

    public static TilePosition[][] splitIntoTiles(int tileWidth, int tileHeight, int w, int h){
        int rows = (h + tileHeight - 1) / tileHeight;
        int cols = (w + tileWidth - 1) / tileWidth;
        TilePosition[][] tiles = new TilePosition[rows][cols];
        for (int r = 0; r < rows; r++) {
            int yStart = r * tileHeight;
            int yEnd = Math.min(yStart + tileHeight, h);
            for (int c = 0; c < cols; c++) {
                int xStart = c * tileWidth;
                int xEnd = Math.min(xStart + tileWidth, w);
                tiles[r][c] = new TilePosition(xStart, xEnd, yStart, yEnd);
            }
        }
        return tiles;
    }

    private static void saveGrayImage(byte[] data, int w, int h, File outFile) {
        try {
            BufferedImage outImg = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
            outImg.getRaster().setDataElements(0, 0, w, h, data);
            ImageIO.write(outImg, "png", outFile);
        } catch (IOException e) {
            throw new RuntimeException("Ошибка сохранения " + outFile, e);
        }
    }

    private static void saveRgbImage(int[] data, int w, int h, File outFile) {
        try {
            BufferedImage outImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            outImg.setRGB(0, 0, w, h, data, 0, w);
            ImageIO.write(outImg, "png", outFile);
        } catch (IOException e) {
            throw new RuntimeException("Ошибка сохранения " + outFile, e);
        }
    }

    public static Task[] createTasks(
            TilePosition[][] tiles,
            int w,
            int h,
            double k,
            File outDir,
            int[] pixels,
            byte[] grayPixels,
            byte[] sobelPixels,
            byte[] yPixels,
            byte[] cbPixels,
            byte[] crPixels,
            int[] roundtripPixels,
            int[] distortPixels
    ) {
        int rows = tiles.length;
        int cols = tiles[0].length;
        int totalTiles = rows * cols;

        
        Task[] tasks = new Task[totalTiles * 5 + 7];

        int saveBase = 5 * totalTiles;
        Task saveGrayTask = new Task(TaskType.SAVE, () ->
                saveGrayImage(grayPixels, w, h, new File(outDir, "gray.png")));
        Task saveSobelTask = new Task(TaskType.SAVE, () ->
                saveGrayImage(sobelPixels, w, h, new File(outDir, "sobel.png")));
        Task saveYTask = new Task(TaskType.SAVE, () ->
                saveGrayImage(yPixels, w, h, new File(outDir, "ycbcr_y.png")));
        Task saveCbTask = new Task(TaskType.SAVE, () ->
                saveGrayImage(cbPixels, w, h, new File(outDir, "ycbcr_cb.png")));
        Task saveCrTask = new Task(TaskType.SAVE, () ->
                saveGrayImage(crPixels, w, h, new File(outDir, "ycbcr_cr.png")));
        Task saveRoundtripTask = new Task(TaskType.SAVE, () ->
                saveRgbImage(roundtripPixels, w, h, new File(outDir, "ycbcr_roundtrip.png")));
        Task saveDistortTask = new Task(TaskType.SAVE, () ->
                saveRgbImage(distortPixels, w, h, new File(outDir, "distort.png")));

        tasks[saveBase + 0] = saveGrayTask;
        tasks[saveBase + 1] = saveSobelTask;
        tasks[saveBase + 2] = saveYTask;
        tasks[saveBase + 3] = saveCbTask;
        tasks[saveBase + 4] = saveCrTask;
        tasks[saveBase + 5] = saveRoundtripTask;
        tasks[saveBase + 6] = saveDistortTask;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                TilePosition pos = tiles[r][c];

                Task grayTask = new Task(TaskType.GRAY, () ->
                        FilteringImgService.grayscaleFilter(pixels, grayPixels, w, pos));
                Task sobelTask = new Task(TaskType.SOBEL, () ->
                        FilteringImgService.sobelFilter(grayPixels, sobelPixels, w, h, pos));
                Task ycbcrTask = new Task(TaskType.YCBCR, () ->
                        FilteringImgService.yCbCrFilter(pixels, yPixels, cbPixels, crPixels, w, pos));
                Task roundtripTask = new Task(TaskType.ROUNDTRIP, () ->
                        FilteringImgService.yCbCrToRgbFilter(yPixels, cbPixels, crPixels, roundtripPixels, w, pos));
                Task distortTask = new Task(TaskType.DISTORT, () ->
                        FilteringImgService.barrelDistortionFilter(pixels, distortPixels, w, h, k, pos));

                tasks[idx] = grayTask;
                tasks[totalTiles + idx] = sobelTask;
                tasks[2 * totalTiles + idx] = ycbcrTask;
                tasks[3 * totalTiles + idx] = roundtripTask;
                tasks[4 * totalTiles + idx] = distortTask;

                // Обратный перевод ждёт свою плитку YCbCr
                ycbcrTask.addChild(roundtripTask);

                // Каждая задача сохранения файла ждёт все плитки своего преобразования
                grayTask.addChild(saveGrayTask);
                sobelTask.addChild(saveSobelTask);
                ycbcrTask.addChild(saveYTask);
                ycbcrTask.addChild(saveCbTask);
                ycbcrTask.addChild(saveCrTask);
                roundtripTask.addChild(saveRoundtripTask);
                distortTask.addChild(saveDistortTask);
            }
        }

        // Связываем каждую плитку Собеля с соседними плитками Серого в том же массиве tasks
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Task sobelTask = tasks[totalTiles + (r * cols + c)];
                for (int nr = Math.max(0, r - 1); nr <= Math.min(rows - 1, r + 1); nr++) {
                    for (int nc = Math.max(0, c - 1); nc <= Math.min(cols - 1, c + 1); nc++) {
                        tasks[nr * cols + nc].addChild(sobelTask);
                    }
                }
            }
        }

        return tasks;
    }

    private static void writeReportJson(
            File outDir,
            String inputPath,
            String mode,
            int threads,
            int strip,
            double k,
            long totalMs,
            Map<TaskType, Long> taskTimesMs
    ) throws IOException {
        String json = String.format(Locale.US,
                "{\n" +
                "  \"input\": \"%s\",\n" +
                "  \"mode\": \"%s\",\n" +
                "  \"threads\": %d,\n" +
                "  \"strip\": %d,\n" +
                "  \"k\": %.4f,\n" +
                "  \"total_ms\": %d,\n" +
                "  \"tasks_ms\": {\n" +
                "    \"GRAY\": %d,\n" +
                "    \"YCBCR\": %d,\n" +
                "    \"ROUNDTRIP\": %d,\n" +
                "    \"DISTORT\": %d,\n" +
                "    \"SOBEL\": %d,\n" +
                "    \"SAVE\": %d\n" +
                "  }\n" +
                "}\n",
                inputPath, mode, threads, strip, k, totalMs,
                taskTimesMs.getOrDefault(TaskType.GRAY, 0L),
                taskTimesMs.getOrDefault(TaskType.YCBCR, 0L),
                taskTimesMs.getOrDefault(TaskType.ROUNDTRIP, 0L),
                taskTimesMs.getOrDefault(TaskType.DISTORT, 0L),
                taskTimesMs.getOrDefault(TaskType.SOBEL, 0L),
                taskTimesMs.getOrDefault(TaskType.SAVE, 0L)
        );
        Files.writeString(Path.of(outDir.getPath(), "report.json"), json);
    }
    
    public static void main(String[] args) throws IOException {
        // Параметры по умолчанию (переопределяются флагами командной строки)
        String inputPath = "Hardi.png";
        String outPath = "result";
        String mode = "par";
        int threads = Runtime.getRuntime().availableProcessors();
        int strip = 256;
        double k = 0.4;

        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--input" -> inputPath = args[++i];
                case "--out" -> outPath = args[++i];
                case "--mode" -> mode = args[++i];
                case "--threads" -> threads = Integer.parseInt(args[++i]);
                case "--strip" -> strip = Integer.parseInt(args[++i]);
                case "--k" -> k = Double.parseDouble(args[++i]);
            }
        }

        File outDir = new File(outPath);
        if (!outDir.exists()) {
            outDir.mkdirs();
        }

        BufferedImage img = ImageIO.read(new File(inputPath));
        
        // Размеры картинки и буферы различных фильтров 
        int w = img.getWidth();
        int h = img.getHeight();
        int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
        byte[] grayPixels = new byte[w * h];
        byte[] sobelPixels = new byte[w * h];
        byte[] yPixels = new byte[w * h];
        byte[] cbPixels = new byte[w * h];
        byte[] crPixels = new byte[w * h];
        int[] roundtripPixels = new int[w * h];
        int[] distortPixels = new int[w * h];
        
        TilePosition[][] tiles = splitIntoTiles(strip, strip, w, h);
        Task[] tasks = createTasks(
                tiles, w, h, k, outDir,
                pixels, grayPixels, sobelPixels,
                yPixels, cbPixels, crPixels,
                roundtripPixels, distortPixels
        );

        long startTime = System.nanoTime();
        Map<TaskType, Long> taskTimesMs = new EnumMap<>(TaskType.class);

        if ("seq".equalsIgnoreCase(mode)) {
            // Последовательный эталонный режим в одном потоке без пула
            Map<TaskType, Long> nanosByType = new EnumMap<>(TaskType.class);
            for (TaskType type : TaskType.values()) {
                nanosByType.put(type, 0L);
            }
            for (Task task : tasks) {
                long t0 = System.nanoTime();
                task.run();
                long dt = System.nanoTime() - t0;
                nanosByType.put(task.getTaskType(), nanosByType.get(task.getTaskType()) + dt);
            }
            for (TaskType type : TaskType.values()) {
                taskTimesMs.put(type, nanosByType.get(type) / 1_000_000L);
            }
        } else {
            // Параллельный режим на собственном пуле потоков
            CustomThreadPool pool = new CustomThreadPool(threads);
            pool.setRemainingTask(tasks.length);

            for (Task task : tasks) {
                if (task.isReady()) {
                    pool.addTask(task);
                }
            }

            pool.awaitCompletion();
            pool.shutdown();

            for (TaskType type : TaskType.values()) {
                taskTimesMs.put(type, pool.getTaskTimeMillis(type));
            }
        }

        long totalMs = (System.nanoTime() - startTime) / 1_000_000L;
        System.out.println("Общее время выполнения: " + totalMs);
        writeReportJson(outDir, inputPath, mode, threads, strip, k, totalMs, taskTimesMs);
    }
}
