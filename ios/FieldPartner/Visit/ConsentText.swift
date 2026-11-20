import Foundation

/// Consent wording shown to the customer. DRAFT: have an attorney in each state
/// you operate in review this before field use, then bump `version`.
enum ConsentText {
  static let version = "draft-2026-11-20"

  static func serviceAuthorization(company: String, fee: Int?, language: String) -> String {
    let feeText = fee.map { String(format: "$%.2f", Double($0) / 100) }
    if language == "es" {
      return """
      Autorizo a \(company) y a su técnico a entrar a esta propiedad para inspeccionar y diagnosticar el problema que describí.
      Soy el dueño o estoy autorizado por el dueño para aprobar esta visita.
      \(feeText.map { "Se cobra una tarifa de diagnóstico de \($0). " } ?? "")No se hará ninguna reparación sin mi aprobación por escrito y un precio.
      Informaré al técnico de cualquier peligro conocido, como olor a gas, alarmas, mascotas o problemas eléctricos.
      Si el técnico encuentra una condición peligrosa, puede apagar el equipo y marcarlo como inseguro.
      """
    }
    return """
    I authorize \(company) and its technician to enter this property to inspect and diagnose the problem I described.
    I am the owner, or I am authorized by the owner to approve this visit.
    \(feeText.map { "A diagnostic fee of \($0) applies. " } ?? "")No repair will be done without my separate written approval and a stated price.
    I will tell the technician about known hazards such as gas smells, alarms, pets, or electrical problems.
    If the technician finds an unsafe condition, they may shut off the equipment and tag it as unsafe.
    """
  }

  static func recording(company: String, language: String) -> String {
    if language == "es" {
      return """
      El técnico usa lentes inteligentes con cámara y micrófono. Con su permiso, graban video y audio de la visita.
      Las grabaciones se usan para seguridad, apoyo al técnico, control de calidad, capacitación y registros del servicio.
      Un supervisor con licencia puede ver la visita en vivo y hablar con el técnico.
      Partes del video y las preguntas del técnico son procesadas por un servicio de inteligencia artificial de un tercero (Google) solo para dar este servicio.
      No vendemos ni publicamos las grabaciones. Puede pedir que se pause o se detenga la grabación en cualquier momento, y pedir una copia o que se borre.
      No se graba en baños ni dormitorios sin su permiso. Por favor avise a otras personas en la casa.
      """
    }
    return """
    The technician wears smart glasses with a camera and microphone. With your permission, they record video and audio of this visit.
    Recordings are used for safety, technician support, quality control, training, service records and resolving disputes.
    A licensed supervisor may watch live and speak with the technician.
    Short parts of the video and the technician's spoken questions are processed by a third-party AI service (currently Google) only to provide this service.
    We do not sell or post recordings. You can ask us to pause or stop recording at any time, and request a copy or deletion.
    We will not record in bathrooms or bedrooms without your permission. Please let others in the home know.
    """
  }

  static let choices: [(id: String, en: String, es: String)] = [
    ("video_audio", "Video and audio OK", "Video y audio OK"),
    ("audio_only", "Audio only, no video", "Solo audio, sin video"),
    ("no_recording", "No recording", "Sin grabación"),
  ]
}
