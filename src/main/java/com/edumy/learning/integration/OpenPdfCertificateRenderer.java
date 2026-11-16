package com.edumy.learning.integration;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Simple certificate layout (P12; the official template is Q-11, still open — swap this bean, the service stays): A4
 * landscape, centred lines, Noto Sans (SIL OFL 1.1, {@code resources/fonts}) embedded with Identity-H encoding so every
 * Vietnamese letter is drawn and extractable. Content = the stored snapshot only (BR-40): student, course, main
 * instructor, issue date (dd/MM/yyyy, Vietnam time), code, verification link. Fonts are read once at start-up (a missing
 * font fails the start, not a request); the PDF is built in memory and never stored (D-48).
 */
@Component
public class OpenPdfCertificateRenderer implements CertificatePdfRenderer {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(VIETNAM);
    private static final Color ACCENT = new Color(0x56, 0x24, 0xD0);
    private static final Color MUTED = new Color(0x55, 0x55, 0x55);

    private final BaseFont regular;
    private final BaseFont bold;

    public OpenPdfCertificateRenderer() {
        this.regular = font("fonts/NotoSans-Regular.ttf");
        this.bold = font("fonts/NotoSans-Bold.ttf");
    }

    @Override
    public byte[] render(CertificatePdfData certificate) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            Document document = new Document(PageSize.A4.rotate(), 64, 64, 72, 56);
            PdfWriter.getInstance(document, out);
            document.addTitle("Chứng chỉ hoàn thành " + certificate.certificateCode());
            document.addCreator("Edumy");
            document.open();
            line(document, "EDUMY", new Font(bold, 16, Font.NORMAL, ACCENT), 0);
            line(document, "CHỨNG CHỈ HOÀN THÀNH KHÓA HỌC", new Font(bold, 28, Font.NORMAL, Color.BLACK), 18);
            line(document, "Chứng nhận", new Font(regular, 15, Font.NORMAL, MUTED), 30);
            line(document, certificate.studentName(), new Font(bold, 30, Font.NORMAL, ACCENT), 10);
            line(document, "đã hoàn thành khóa học", new Font(regular, 15, Font.NORMAL, MUTED), 18);
            line(document, certificate.courseTitle(), new Font(bold, 20, Font.NORMAL, Color.BLACK), 8);
            if (certificate.instructorName() != null && !certificate.instructorName().isBlank()) {
                line(document, "Giảng viên: " + certificate.instructorName(),
                        new Font(regular, 14, Font.NORMAL, Color.BLACK), 14);
            }
            line(document, "Ngày cấp: " + DATE.format(certificate.issuedAt()),
                    new Font(regular, 13, Font.NORMAL, Color.BLACK), 30);
            line(document, "Mã chứng chỉ: " + certificate.certificateCode(),
                    new Font(bold, 13, Font.NORMAL, Color.BLACK), 6);
            line(document, "Xác minh tại: " + certificate.verifyUrl(), new Font(regular, 11, Font.NORMAL, MUTED), 6);
            document.close();
            return out.toByteArray();
        } catch (RuntimeException e) { // DocumentException / ExceptionConverter of OpenPDF are unchecked
            throw new CertificateRenderingException("certificate PDF could not be produced", e);
        }
    }

    private static void line(Document document, String text, Font font, float spacingBefore) {
        Paragraph paragraph = new Paragraph(text, font);
        paragraph.setAlignment(Element.ALIGN_CENTER);
        paragraph.setSpacingBefore(spacingBefore);
        document.add(paragraph);
    }

    private static BaseFont font(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            byte[] bytes = in.readAllBytes();
            return BaseFont.createFont(path.substring(path.lastIndexOf('/') + 1), BaseFont.IDENTITY_H,
                    BaseFont.EMBEDDED, BaseFont.CACHED, bytes, null);
        } catch (IOException e) {
            throw new UncheckedIOException("certificate font missing: " + path, e);
        }
    }
}
