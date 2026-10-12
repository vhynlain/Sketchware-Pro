package mod.ai.andiasriefail;

/**
 * Callback untuk menerima potongan teks (token) secara real-time selama streaming.
 *
 * <p>Semua metode dipanggil dari thread background; UI wajib mem-post ke main thread sendiri.
 */
public interface AiStreamCallback {

    /**
     * Dipanggil setiap kali potongan teks baru tiba.
     *
     * @param delta potongan teks (bisa satu karakter, satu kata, atau satu baris — tergantung provider)
     */
    void onDelta(String delta);

    /**
     * Dipanggil ketika streaming selesai dengan sukses.
     *
     * @param fullText teks lengkap yang sudah terkumpul (gabungan semua delta)
     */
    void onComplete(String fullText);

    /**
     * Dipanggil jika terjadi error atau streaming dihentikan oleh pengguna.
     *
     * @param error pesan error yang bisa ditampilkan ke pengguna
     * @param cancelled {@code true} jika dihentikan via {@code cancel()}, bukan error server
     */
    void onError(String error, boolean cancelled);
}
