package com.voidterm.voice;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Simple tokenizer for Parakeet TDT v3 output decoding.
 * Loads vocab.txt (format: "token id" per line) and decodes token ID sequences to text.
 *
 * SentencePiece conventions: \u2581 (▁) represents word boundaries (replaced with spaces).
 * Blank token (ID 8192) is filtered during the decode loop, not here.
 */
public class ParakeetTokenizer {

    private static final String TAG = "ParakeetTokenizer";
    static final int BLANK_ID = 8192;
    static final int VOCAB_SIZE = 8193;

    private final Map<Integer, String> vocab = new HashMap<>();

    /**
     * Load vocabulary from file.
     * @param vocabFile Path to vocab.txt
     * @throws IOException if file cannot be read
     */
    public void load(File vocabFile) throws IOException {
        vocab.clear();
        try (BufferedReader reader = new BufferedReader(new FileReader(vocabFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int lastSpace = line.lastIndexOf(' ');
                if (lastSpace <= 0) continue;
                String token = line.substring(0, lastSpace);
                try {
                    int id = Integer.parseInt(line.substring(lastSpace + 1));
                    vocab.put(id, token);
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Skipping invalid vocab line: " + line);
                }
            }
        }
        Log.i(TAG, "Loaded " + vocab.size() + " tokens from " + vocabFile.getName());
    }

    /**
     * Decode a sequence of token IDs to text.
     * Replaces SentencePiece word boundary markers (\u2581) with spaces.
     *
     * @param tokenIds array of token IDs (blank tokens should already be filtered)
     * @return decoded text string
     */
    public String decode(int[] tokenIds) {
        StringBuilder sb = new StringBuilder();
        for (int id : tokenIds) {
            String token = vocab.get(id);
            if (token != null) {
                sb.append(token);
            }
        }
        // Replace SentencePiece word boundary marker with space
        String text = sb.toString().replace('\u2581', ' ');
        // Clean up leading space and double spaces
        return text.trim().replaceAll("  +", " ");
    }

    /** Check if vocabulary is loaded. */
    public boolean isLoaded() {
        return !vocab.isEmpty();
    }

    /** Get the vocabulary size. */
    public int size() {
        return vocab.size();
    }
}
