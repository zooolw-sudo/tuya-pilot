package pl.zooolw.tuyapilot.core;

/** Błąd komunikacji z urządzeniem Tuya albo z chmurą – z rodzajem, żeby aplikacja mogła podpowiedzieć, co zrobić. */
public class TuyaException extends Exception {

    public enum Kind {
        /** Nie da się połączyć z urządzeniem (wyłączone, zły adres IP, poza siecią domową bez VPN). */
        UNREACHABLE,
        /** Urządzenie nie odpowiedziało w czasie. */
        TIMEOUT,
        /** Klucz lokalny (local key) nie pasuje – zwykle po ponownym sparowaniu urządzenia. */
        BAD_KEY,
        /** Urządzenie odpowiada w innej wersji protokołu niż ustawiona. */
        BAD_VERSION,
        /** Urządzenie zerwało połączenie – tak reaguje i na zły klucz, i na złą wersję protokołu. */
        KEY_OR_VERSION,
        /** Ramka nie pasuje do protokołu. */
        PROTOCOL,
        /** Błąd zwrócony przez chmurę Tuya. */
        CLOUD
    }

    public final Kind kind;

    public TuyaException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public TuyaException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    /** Opis po polsku dla użytkownika. */
    public String userMessage() {
        switch (kind) {
            case UNREACHABLE:
                return "Brak połączenia z urządzeniem. Sprawdź, czy jest włączone i czy telefon jest w sieci domowej albo ma włączony Tailscale.";
            case TIMEOUT:
                return "Urządzenie nie odpowiedziało na czas.";
            case BAD_KEY:
                return "Klucz lokalny nie pasuje. Pobierz klucze ponownie z chmury Tuya (po ponownym sparowaniu urządzenia klucz się zmienia).";
            case BAD_VERSION:
                return "Urządzenie używa innej wersji protokołu. Ustaw wersję na „auto” w ustawieniach urządzenia.";
            case KEY_OR_VERSION:
                return "Urządzenie zerwało połączenie: nie pasuje klucz lokalny albo wersja protokołu. Ustaw wersję na „auto”, a jeśli to nie pomoże – pobierz klucze ponownie.";
            case CLOUD:
                return getMessage();
            default:
                return "Błąd komunikacji: " + getMessage();
        }
    }
}
