package io.github.mangi.eta.resources;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** Exercises the format string used by Compose accessibility for progress nodes. */
public class AccessibilityPercentResourceTest {
    private static Path resources() {
        Path root = Path.of(System.getProperty("user.dir"));
        if (Files.isDirectory(root.resolve("src/main/res"))) return root.resolve("src/main/res");
        return root.resolve("app/src/main/res");
    }

    private static void check(String directory, Locale locale, boolean chinese) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);
        NodeList strings = factory.newDocumentBuilder().parse(
                resources().resolve(directory).resolve("strings.xml").toFile())
                .getElementsByTagName("string");
        String template = null;
        for (int i = 0; i < strings.getLength(); i++) {
            Element element = (Element) strings.item(i);
            if ("template_percent".equals(element.getAttribute("name"))) {
                template = element.getTextContent();
                break;
            }
        }
        assertNotNull(directory + " must expose template_percent", template);
        for (int value : new int[]{0, 1, 37, 99, 100}) {
            String expected = chinese ? value + "%\u3002" : value + " percent.";
            assertEquals(directory + " at " + value, expected, String.format(locale, template, value));
        }
    }

    @Test public void englishProgressDescriptionFormats() throws Exception {
        check("values", Locale.US, false);
    }

    @Test public void simplifiedChineseProgressDescriptionFormats() throws Exception {
        check("values-b+zh+Hans", Locale.SIMPLIFIED_CHINESE, true);
    }

    @Test public void traditionalChineseProgressDescriptionFormats() throws Exception {
        check("values-b+zh+Hant", Locale.TRADITIONAL_CHINESE, true);
    }
}
