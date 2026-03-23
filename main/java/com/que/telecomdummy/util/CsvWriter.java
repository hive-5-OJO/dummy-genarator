package com.que.telecomdummy.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

public final class CsvWriter implements Closeable {
    private final BufferedWriter w;

    public CsvWriter(Path path, List<String> header) throws IOException {
        Files.createDirectories(path.getParent());
        this.w = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        writeRow(header);
    }

    public void writeRow(List<String> cols) throws IOException {
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) w.write(',');
            w.write(escape(cols.get(i)));
        }
        w.newLine();
    }

    private String escape(String s) {
        if (s == null) return "";
        boolean need = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (!need) return s;
        String t = s.replace("\"", "\"\"");
        return "\"" + t + "\"";
    }

    public void flush() throws IOException { w.flush(); }

    @Override
    public void close() throws IOException { w.close(); }
}
