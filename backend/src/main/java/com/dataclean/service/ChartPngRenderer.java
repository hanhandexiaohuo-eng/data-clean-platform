package com.dataclean.service;

import com.dataclean.dto.ChartDataVO;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 服务端 PNG 图表渲染（Java2D，无额外依赖）。
 *
 * <p>只渲染已聚合、已截断的 {@link ChartDataVO}，不会重新读取全量明细；
 * 用于“受控 PNG 导出”，导出行为由 ExportService 统一记录任务与配额。
 */
@Component
public class ChartPngRenderer {

    private static final Color[] PALETTE = {
            new Color(0x63, 0x66, 0xF1), new Color(0x22, 0xC5, 0x5E),
            new Color(0xF5, 0x9E, 0x0B), new Color(0xEF, 0x44, 0x44),
            new Color(0x06, 0xB6, 0xD4), new Color(0x8B, 0x5C, 0xF6),
            new Color(0xEC, 0x48, 0x99), new Color(0x84, 0xCC, 0x16)
    };

    public byte[] render(String title, ChartDataVO data, int width, int height) {
        int w = clamp(width, 480, 2000);
        int h = clamp(height, 300, 1400);
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);

            g.setColor(new Color(0x1F, 0x29, 0x37));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
            g.drawString(title == null || title.isBlank() ? "图表导出" : title, 24, 34);

            if (data == null || data.getSeries() == null || data.getSeries().isEmpty()
                    || data.getSeries().stream().allMatch(s -> s.getData() == null || s.getData().isEmpty())) {
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
                g.setColor(new Color(0x6B, 0x72, 0x80));
                g.drawString("暂无数据", 24, 70);
                return write(image);
            }

            String chartType = data.getChartType() == null ? "bar" : data.getChartType().toLowerCase(Locale.ROOT);
            if ("pie".equals(chartType)) {
                drawPie(g, data, w, h);
            } else if ("scatter".equals(chartType)) {
                drawScatter(g, data, w, h);
            } else {
                drawCartesian(g, data, w, h, "line".equals(chartType));
            }

            if (data.getNotice() != null && !data.getNotice().isBlank()) {
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                g.setColor(new Color(0x9C, 0xA3, 0xAF));
                g.drawString(cut(data.getNotice(), 110), 24, h - 14);
            }
            return write(image);
        } finally {
            g.dispose();
        }
    }

    private void drawCartesian(Graphics2D g, ChartDataVO data, int w, int h, boolean line) {
        List<Object> values = data.getSeries().get(0).getData();
        List<Double> numbers = new ArrayList<>();
        double max = 0;
        for (Object value : values) {
            double number = toDouble(value);
            numbers.add(number);
            max = Math.max(max, number);
        }
        if (max <= 0) {
            max = 1;
        }
        int left = 64;
        int right = 28;
        int top = 64;
        int bottom = 56;
        int plotW = Math.max(1, w - left - right);
        int plotH = Math.max(1, h - top - bottom);

        g.setColor(new Color(0xE5, 0xE7, 0xEB));
        g.setStroke(new BasicStroke(1f));
        for (int i = 0; i <= 4; i++) {
            int y = top + plotH - plotH * i / 4;
            g.drawLine(left, y, left + plotW, y);
            g.setColor(new Color(0x9C, 0xA3, 0xAF));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            g.drawString(format(max * i / 4), 10, y + 4);
            g.setColor(new Color(0xE5, 0xE7, 0xEB));
        }

        int count = numbers.size();
        if (line) {
            g.setColor(PALETTE[0]);
            g.setStroke(new BasicStroke(2.4f));
            Integer prevX = null;
            Integer prevY = null;
            for (int i = 0; i < count; i++) {
                int x = left + (count <= 1 ? plotW / 2 : plotW * i / (count - 1));
                int y = top + plotH - (int) (plotH * numbers.get(i) / max);
                if (prevX != null) {
                    g.drawLine(prevX, prevY, x, y);
                }
                g.fillOval(x - 3, y - 3, 6, 6);
                prevX = x;
                prevY = y;
            }
        } else {
            int slot = Math.max(1, plotW / Math.max(1, count));
            int barWidth = Math.max(2, (int) (slot * 0.64));
            for (int i = 0; i < count; i++) {
                int x = left + slot * i + (slot - barWidth) / 2;
                int barHeight = (int) (plotH * numbers.get(i) / max);
                g.setColor(PALETTE[i % PALETTE.length]);
                g.fillRect(x, top + plotH - barHeight, barWidth, barHeight);
            }
        }

        List<String> labels = data.getXAxisData();
        if (labels != null && !labels.isEmpty()) {
            g.setColor(new Color(0x6B, 0x72, 0x80));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            int step = Math.max(1, labels.size() / 10);
            for (int i = 0; i < labels.size(); i += step) {
                int x = count <= 1 ? left + plotW / 2
                        : left + (count <= 1 ? 0 : plotW * i / Math.max(1, count - 1));
                g.drawString(cut(String.valueOf(labels.get(i)), 10), x - 10, top + plotH + 20);
            }
        }
    }

    private void drawPie(Graphics2D g, ChartDataVO data, int w, int h) {
        List<Object> values = data.getSeries().get(0).getData();
        double total = values.stream().mapToDouble(this::toDouble).sum();
        if (total <= 0) {
            return;
        }
        int size = Math.min(w, h) - 140;
        int x = 60;
        int y = 60;
        double start = 90;
        for (int i = 0; i < values.size(); i++) {
            double angle = 360.0 * toDouble(values.get(i)) / total;
            g.setColor(PALETTE[i % PALETTE.length]);
            g.fill(new Arc2D.Double(x, y, size, size, start, -angle, Arc2D.PIE));
            start -= angle;
        }
        List<String> labels = data.getXAxisData();
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        int legendX = x + size + 24;
        for (int i = 0; i < values.size() && i < 12; i++) {
            int legendY = y + 16 + i * 20;
            g.setColor(PALETTE[i % PALETTE.length]);
            g.fillRect(legendX, legendY - 9, 10, 10);
            g.setColor(new Color(0x37, 0x41, 0x51));
            String label = labels != null && i < labels.size() ? labels.get(i) : "项 " + (i + 1);
            g.drawString(cut(label, 18) + "  " + format(toDouble(values.get(i))), legendX + 16, legendY);
        }
    }

    private void drawScatter(Graphics2D g, ChartDataVO data, int w, int h) {
        List<Object> points = data.getSeries().get(0).getData();
        double max = 1;
        List<double[]> parsed = new ArrayList<>();
        for (Object point : points) {
            double[] xy = parsePoint(point);
            if (xy != null) {
                parsed.add(xy);
                max = Math.max(max, Math.max(Math.abs(xy[0]), Math.abs(xy[1])));
            }
        }
        int left = 56;
        int top = 60;
        int plotW = w - left - 32;
        int plotH = h - top - 48;
        g.setColor(new Color(0xE5, 0xE7, 0xEB));
        g.drawRect(left, top, plotW, plotH);
        g.setColor(PALETTE[0]);
        for (double[] xy : parsed) {
            int x = left + (int) (plotW * (xy[0] + max) / (2 * max));
            int y = top + plotH - (int) (plotH * (xy[1] + max) / (2 * max));
            g.fillOval(x - 3, y - 3, 6, 6);
        }
    }

    private double[] parsePoint(Object point) {
        if (point instanceof List<?> list && list.size() >= 2) {
            return new double[]{toDouble(list.get(0)), toDouble(list.get(1))};
        }
        if (point instanceof Object[] array && array.length >= 2) {
            return new double[]{toDouble(array[0]), toDouble(array[1])};
        }
        return null;
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String format(double value) {
        if (Math.abs(value) >= 1_0000_0000) {
            return String.format(Locale.ROOT, "%.1f亿", value / 1_0000_0000);
        }
        if (Math.abs(value) >= 10_000) {
            return String.format(Locale.ROOT, "%.1f万", value / 10_000);
        }
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("\\.00$", "").replaceAll("(\\.[0-9])0$", "$1");
    }

    private String cut(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private byte[] write(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PNG 渲染失败", e);
        }
    }
}
