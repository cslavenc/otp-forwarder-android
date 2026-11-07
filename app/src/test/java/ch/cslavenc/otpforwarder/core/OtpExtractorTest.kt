package ch.cslavenc.otpforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class OtpExtractorTest {
    private fun check(cases: List<Pair<String, String?>>) {
        for ((sms, expected) in cases) assertEquals(sms, expected, OtpExtractor.extract(sms))
    }

    @Test
    fun `finds the code in realistic SMS`() = check(
        listOf(
            "G-482913 is your Google verification code." to "482913",
            "Your WhatsApp code: 123-456. Don't share this code with others" to "123456",
            "PostFinance: mTAN für Zahlung CHF 1'250.00 an Muster AG: 845 112" to "845112",
            "Ihr Bestätigungscode lautet 739201. Gültig 10 Minuten." to "739201",
            "UBS: Ihr Sicherheitscode für das Login vom 07.11.2026 um 14:35 lautet 558192" to "558192",
            "Votre code de confirmation est 904417. Il expire à 14h35." to "904417",
            "Il tuo codice di verifica è 318274, valido fino al 08/11/2026" to "318274",
            "TWINT: Dein Code lautet 4821" to "4821",
            "Your Microsoft account security code is 7Q4XK2" to "7Q4XK2",
            "Card ending ****1234 charged CHF 12.50. Code: 662019" to "662019",
            "Your PIN is 0472" to "0472",
            "Use 284710 as your login code for Revolut" to "284710",
            "Your code is 123456.\n\n@example.com #123456" to "123456",
            "Ihre TAN: 3F9K2A" to "3F9K2A",
            "SMS-TAN 482913 für Überweisung von EUR 500.00" to "482913",
            "Votre mot de passe temporaire: 83kd92" to "83kd92",
            "Code for +41 79 123 45 67: 552901" to "552901",
            "Betrag CHF 12.- Code 4410" to "4410",
            LONG_SMS to "771203",
            "Ihr Code für die Zahlung von CHF 1'250.00 an Muster AG: 839201" to "839201",
            "G-123456 is your Google verification code." to "123456",
            "Use 1234567 as Microsoft account security code" to "1234567",
            "Votre code de sécurité est 482913. Il est valable 10 minutes." to "482913",
            "Il tuo codice è 5831" to "5831",
            "Swisscom: 482 913 ist Ihr Login-Code" to "482913",
            "Your code is 4KD8P2." to "4KD8P2",
            "Bestätigen Sie die Zahlung an CH93 0076 2011 6238 5295 7. TAN: 7712" to "7712",
            "2026 Code: 5831" to "5831",
            "Code für die Zahlung von CHF 1250 an Muster AG: 482913" to "482913",
            "Code für die Zahlung vom 2026/11/08: 4821" to "4821",
            "4821 ist Ihr Code. Fragen: 044 123 456 78" to "4821",
            "Your verification code is 482913. If you didn't request this code, call 0800 123 456." to "482913",
            "Your code is 482913. Never share this code. Ref 7731" to "482913",
            "Your code is 482913. Our staff will never ask for the code. Reply STOP to 4545" to "482913",
            "482913 ist Ihr Code. Hotline: 0848 888 710" to "482913",
            "482913 ist Ihr mTAN für die Zahlung an CH93 0076 2011 6238 5295 7" to "482913",
            "PostFinance: Code 482913 zur Bestätigung der Zahlung an CH93 0076 2011 6238 5295 7" to "482913",
            "482913 ist Ihr Code für die Zahlung von 1250 CHF" to "482913",
            "Code 482913 bestätigt die Zahlung mit Karte ****1234" to "482913",
            "<#> 482913 is your verification code. FA+9qCX9VSu" to "482913",
            "Ihre smsTAN für die Überweisung lautet 482913" to "482913",
            "Votre numéro de vérification : 482913" to "482913",
            "Ihr Code lautet 482\u00A0913" to "482913",
            "Ihr Code lautet 482\u202F913" to "482913",
            "\uD83D\uDD10 Code: 482913 \uD83D\uDD10" to "482913",
            "Code:\u200F482913\u200E" to "482913",
        ),
    )

    @Test
    fun `random and adversarial SMS never yield a malformed code or one without a keyword`() {
        val random = Random(20261108)
        repeat(5_000) {
            val withKeyword = random.nextBoolean()
            val pool = if (withKeyword) KEYWORD_PIECES + NOISE else NOISE
            val sms = buildString { repeat(random.nextInt(1, 30)) { append(pool.random(random)) } }
            val code = try {
                OtpExtractor.extract(sms)
            } catch (e: RuntimeException) {
                throw AssertionError("threw on: $sms", e)
            }
            if (!withKeyword) assertNull(sms, code)
            if (code != null) {
                assertTrue("$code from: $sms", Regex("[A-Za-z0-9]{4,10}").matches(code))
                // Grouped codes drop one space or hyphen; nothing else may be invented or reordered.
                assertTrue("$code from: $sms", sms.filterNot { it == '-' || it.isWhitespace() }.contains(code))
            }
        }
    }

    @Test
    fun `returns null without a usable code`() = check(
        listOf(
            "Code: 123" to null,
            "Code: 12345678901" to null,
            "Your appointment is on 12.11.2026 at 09:30. Call 044 123 45 67." to null,
            "Ihr Paket kommt am 09.11. zwischen 10:00 und 12:00" to null,
            "Code: ٤٨٢٩١٣" to null,
            "Code: ４８２９１３" to null,
            "Hello 123456" to null,
            "Ihre Bestellung 12345678 wurde versandt" to null,
            "Termin am 08.11.2026 um 14:30, Info 079 123 45 67" to null,
            "Code: ABCDEF" to null,
            "CHF 120, Karte ****1234" to null,
        ),
    )

    @Test
    fun `adversarial long bodies return quickly`() {
        val inputs = listOf("Code " + "1".repeat(20_000), "Code " + "*".repeat(20_000), "Code " + "a1b2 ".repeat(4_000))
        val start = System.nanoTime()
        for (sms in inputs) assertNull(OtpExtractor.extract(sms))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms < 500)
    }

    private companion object {
        val KEYWORD_PIECES = listOf("Code", "code: ", "TAN ", "PIN", "otp", "Verif", "Bestätigung", "mot de passe ")

        /** Code-like noise that cannot spell any keyword (no c-o-d-e, t, p, i, v… letters). */
        val NOISE = listOf(
            "0", "1", "4", "9", "12", "345", "6789", "482913", "A1b2C3", "a", "Z", "ß", "G-", "CH93", "+41", "00",
            " ", "\u00A0", "\u202F", "\n", "-", ".", ",", "'", "’", ":", "/", "(", ")", "+", "<#>",
            "CHF", "€", "$", "****", "•••", "xxxx", "\uD83D\uDD10", "\u200F", "٤", "４", "²", "\uD800",
        )
        val LONG_SMS = "Sehr geehrte Kundin, sehr geehrter Kunde. Am 07.11.2026 um 09:12 wurde eine Zahlung " +
            "von CHF 2'480.50 an Muster Immobilien AG, Konto CH93 0076 2011 6238 5295 7, Referenz 2026-118, " +
            "erfasst. Falls Sie diese Zahlung nicht kennen, rufen Sie uns an: +41 58 123 45 67. " +
            "Zur Freigabe geben Sie bitte folgenden Code ein: 771203"
    }
}
