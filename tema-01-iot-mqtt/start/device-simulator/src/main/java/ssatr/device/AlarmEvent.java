package ssatr.device;

/**
 * Evenimentul pus de taskul periodic în coada de alarme.
 *
 * De ce transmitem și pragul, nu doar temperatura?
 * Pragul se poate schimba oricând (comanda SET_THRESHOLD, executată de taskul
 * aperiodic). Dacă taskul sporadic ar citi pragul din PeriodicTelemetryTask în
 * momentul tratării, ar putea publica o temperatură comparată cu un alt prag
 * decât cel care a declanșat alarma. Evenimentul este deci un „instantaneu”
 * consistent: temperatura, pragul depășit și momentul sosirii.
 *
 * arrivalNanos (timp monoton) este momentul în care a apărut evenimentul;
 * intervalul minim între sosiri și timpul de răspuns se măsoară de la el,
 * nu de la momentul în care taskul sporadic îl scoate din coadă.
 */
public record AlarmEvent(double temperature, double threshold, long arrivalNanos) {
}
