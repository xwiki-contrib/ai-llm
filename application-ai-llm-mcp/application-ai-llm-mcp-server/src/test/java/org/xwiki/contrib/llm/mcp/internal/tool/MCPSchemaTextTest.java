/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.llm.mcp.internal.tool;

import org.junit.jupiter.api.Test;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.BooleanClass;
import com.xpn.xwiki.objects.classes.ComputedFieldClass;
import com.xpn.xwiki.objects.classes.DBListClass;
import com.xpn.xwiki.objects.classes.DateClass;
import com.xpn.xwiki.objects.classes.NumberClass;
import com.xpn.xwiki.objects.classes.PasswordClass;
import com.xpn.xwiki.objects.classes.PropertyClass;
import com.xpn.xwiki.objects.classes.StaticListClass;
import com.xpn.xwiki.objects.classes.StringClass;
import com.xpn.xwiki.objects.classes.TextAreaClass;
import com.xpn.xwiki.objects.classes.UsersClass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link MCPSchemaText}: the field-line grammar is a contract agents parse, so the broad
 * rendering is pinned as an exact-text assertion over real oldcore property classes, not substring checks
 * over mocks.
 *
 * @version $Id$
 */
class MCPSchemaTextTest
{
    private static final String SECRET_SQL = "select internal_column from secret_table";

    private static final String SECRET_SCRIPT = "$services.secretService.compute()";

    private final XWikiContext context = mock(XWikiContext.class);

    private static <T extends PropertyClass> T field(T property, String name, String prettyName, int number)
    {
        property.setName(name);
        // The oldcore constructors seed a default pretty name (the type's display label, e.g. "Static
        // List"); a null argument here means "no pretty name", so the seed is cleared explicitly.
        property.setPrettyName(prettyName != null ? prettyName : "");
        property.setNumber(number);
        return property;
    }

    private static void add(BaseClass xclass, PropertyClass property)
    {
        xclass.addField(property.getName(), property);
    }

    @Test
    void broadClassRendersEveryFieldKindInDisplayOrder()
    {
        BaseClass xclass = new BaseClass();

        add(xclass, field(new StringClass(), "title", "Title", 1));

        StaticListClass category = field(new StaticListClass(), "category", "Category", 2);
        category.setValues("News|Personal|Other");
        category.setMultiSelect(true);
        category.setDefaultValue("News");
        add(xclass, category);

        NumberClass amount = field(new NumberClass(), "amount", "Amount", 3);
        amount.setNumberType("integer");
        add(xclass, amount);

        BooleanClass published = field(new BooleanClass(), "published", "Published", 4);
        published.setDisplayType("yesno");
        published.setDefaultValue(1);
        add(xclass, published);

        DateClass publishDate = field(new DateClass(), "publishDate", "Publish date", 5);
        publishDate.setDateFormat("dd/MM/yyyy HH:mm:ss");
        add(xclass, publishDate);

        // Content type left unset: the stored default is wiki content, rendered as "wiki".
        add(xclass, field(new TextAreaClass(), "content", "Content", 6));

        TextAreaClass setup = field(new TextAreaClass(), "setup", "Setup", 7);
        setup.setContentType("VelocityCode");
        add(xclass, setup);

        add(xclass, field(new UsersClass(), "reviewer", "Reviewer", 8));

        add(xclass, field(new PasswordClass(), "secret", "Secret", 9));

        DBListClass source = field(new DBListClass(), "source", "Source", 10);
        source.setSql(SECRET_SQL);
        add(xclass, source);

        ComputedFieldClass total = field(new ComputedFieldClass(), "total", "Total", 11);
        total.setScript(SECRET_SCRIPT);
        add(xclass, total);

        // Pretty name equal to the field name: the quoted segment is omitted entirely.
        StringClass code = field(new StringClass(), "code", "code", 12);
        code.setValidationRegExp("^[A-Z]{3}$");
        code.setValidationMessage("Three uppercase letters");
        add(xclass, code);

        StringClass legacyField = field(new StringClass(), "legacyField", "Legacy", 13);
        legacyField.setDisabled(true);
        add(xclass, legacyField);
        StringClass oldFlag = field(new StringClass(), "oldFlag", "Old flag", 14);
        oldFlag.setDisabled(true);
        add(xclass, oldFlag);

        String rendered = MCPSchemaText.render(xclass, this.context);

        assertEquals("""
            FIELDS (display order)
              title: String "Title"
              category: StaticList(News|Personal|Other) "Category" multiselect default=News
              amount: Number(integer) "Amount"
              published: Boolean(yesno) "Published" default=1
              publishDate: Date(dd/MM/yyyy HH:mm:ss) "Publish date"
              content: TextArea(wiki) "Content"
              setup: TextArea(velocityCode) "Setup" (executable script)
              reviewer: Users "Reviewer"
              secret: Password "Secret" (values masked in query results)
              source: DBList "Source" (values come from a database query)
              total: ComputedField "Total" (computed by a script; read-only)
              code: String [validation: ^[A-Z]{3}$ "Three uppercase letters"]

            DISABLED FIELDS: legacyField, oldFlag""", rendered);

        // The security requirement behind the fixed notes: admin-authored query and script bodies (and any
        // password storage detail) never reach the wire.
        assertFalse(rendered.contains(SECRET_SQL), rendered);
        assertFalse(rendered.contains(SECRET_SCRIPT), rendered);
        assertFalse(rendered.contains("secret_table"), rendered);
    }

    @Test
    void velocityWikiContentAlsoCarriesTheExecutableNote()
    {
        BaseClass xclass = new BaseClass();
        TextAreaClass macro = field(new TextAreaClass(), "macro", null, 1);
        macro.setContentType("VelocityWiki");
        add(xclass, macro);

        String rendered = MCPSchemaText.render(xclass, this.context);

        assertTrue(rendered.contains("macro: TextArea(velocityWiki) (executable script)"), rendered);
    }

    @Test
    void pureTextContentRendersAsPlainAndNoNote()
    {
        BaseClass xclass = new BaseClass();
        TextAreaClass notes = field(new TextAreaClass(), "notes", null, 1);
        notes.setContentType("PureText");
        add(xclass, notes);

        String rendered = MCPSchemaText.render(xclass, this.context);

        assertTrue(rendered.contains("notes: TextArea(plain)"), rendered);
        assertFalse(rendered.contains("executable"), rendered);
    }

    @Test
    void unsetContentTypePlaceholderResolvesToWikiContent()
    {
        BaseClass xclass = new BaseClass();
        TextAreaClass notes = field(new TextAreaClass(), "notes", null, 1);
        // The class editor stores the literal "---" placeholder when no content type is selected; the
        // platform treats such a field as wiki content, so the schema line must say "wiki", never "---".
        notes.setContentType("---");
        add(xclass, notes);

        String rendered = MCPSchemaText.render(xclass, this.context);

        assertTrue(rendered.contains("notes: TextArea(wiki)"), rendered);
        assertFalse(rendered.contains("---"), rendered);
    }

    @Test
    void unsetContentTypeWithPureTextEditorOmitsTheUndecidableDetail()
    {
        BaseClass xclass = new BaseClass();
        TextAreaClass notes = field(new TextAreaClass(), "notes", null, 1);
        notes.setContentType("---");
        // A pure text editor is compatible with several content kinds, so with the content type unset the
        // kind is genuinely undecidable: the detail is omitted rather than guessed.
        notes.setEditor("PureText");
        add(xclass, notes);

        String rendered = MCPSchemaText.render(xclass, this.context);

        assertTrue(rendered.endsWith("notes: TextArea"), rendered);
        assertFalse(rendered.contains("TextArea("), rendered);
    }

    @Test
    void staticListDefaultOutsideTheValuesCarriesTheMismatchMarker()
    {
        BaseClass xclass = new BaseClass();
        // The live shape this guards: a default typed with different casing/punctuation than any allowed
        // value. The comparison is case-sensitive because only a byte-identical default is usable.
        StaticListClass docType = field(new StaticListClass(), "docType", null, 1);
        docType.setValues("tutorial|howto|reference|explanation");
        docType.setDefaultValue("How-To");
        add(xclass, docType);

        StaticListClass docKind = field(new StaticListClass(), "docKind", null, 2);
        docKind.setValues("tutorial|howto|reference|explanation");
        docKind.setDefaultValue("howto");
        add(xclass, docKind);

        String rendered = MCPSchemaText.render(xclass, this.context);

        assertTrue(rendered.contains(
            "docType: StaticList(tutorial|howto|reference|explanation) default=How-To (not among the values)"),
            rendered);
        // An exact match carries no marker: the line ends at the default value.
        assertTrue(rendered.endsWith("docKind: StaticList(tutorial|howto|reference|explanation) default=howto"),
            rendered);
    }

    @Test
    void booleanWithoutExplicitDefaultCarriesNoDefaultModifier()
    {
        BaseClass xclass = new BaseClass();
        // BooleanClass.getDefaultValue() returns -1 when no default was ever set: no modifier renders.
        add(xclass, field(new BooleanClass(), "flag", null, 1));

        String rendered = MCPSchemaText.render(xclass, this.context);

        assertTrue(rendered.contains("flag: Boolean(yesno)"), rendered);
        assertFalse(rendered.contains("default="), rendered);
    }

    @Test
    void blankPrettyNameIsOmitted()
    {
        BaseClass xclass = new BaseClass();
        add(xclass, field(new StringClass(), "plain", null, 1));

        String rendered = MCPSchemaText.render(xclass, this.context);

        assertTrue(rendered.contains("plain: String"), rendered);
        assertFalse(rendered.contains("\""), rendered);
    }

    @Test
    void newlinesInWikiAuthoredFragmentsCannotForgeFieldLines()
    {
        BaseClass xclass = new BaseClass();

        StringClass sneaky = field(new StringClass(), "sneaky", "Nice name\n  fake: Password", 1);
        sneaky.setValidationRegExp("^a\nb$");
        sneaky.setValidationMessage("first\nsecond");
        add(xclass, sneaky);

        StaticListClass options = field(new StaticListClass(), "options", null, 2);
        options.setValues("one|two\nthree");
        add(xclass, options);

        String rendered = MCPSchemaText.render(xclass, this.context);

        // Every wiki-authored fragment is stripped of the newline family: the block stays exactly one line
        // per field, so a crafted pretty name, list value or validation text cannot forge a field line.
        assertEquals(3, rendered.split("\n", -1).length, rendered);
        assertTrue(rendered.contains("sneaky: String \"Nice name  fake: Password\""), rendered);
        assertTrue(rendered.contains("[validation: ^ab$ \"firstsecond\"]"), rendered);
        assertTrue(rendered.contains("options: StaticList(one|twothree)"), rendered);
    }

    @Test
    void giantStaticListValuesAreClampedAtAValueBoundary()
    {
        BaseClass xclass = new BaseClass();
        StaticListClass options = field(new StaticListClass(), "options", null, 1);
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            if (i > 0) {
                values.append('|');
            }
            values.append("value").append(i);
        }
        options.setValues(values.toString());
        add(xclass, options);

        String rendered = MCPSchemaText.render(xclass, this.context);
        String line = rendered.split("\n")[1];

        // A hostile editor storing megabytes of list values must not blow up the response: the joined
        // values are budgeted, cut at a value boundary (never mid-value) and end with the fixed suffix.
        assertTrue(line.length() < 400, line);
        String detail = line.substring(line.indexOf('(') + 1, line.lastIndexOf(')'));
        assertTrue(detail.startsWith("value0|value1|"), detail);
        assertTrue(detail.matches("(value\\d+\\|)*value\\d+… \\(values truncated\\)"), detail);
    }

    @Test
    void oversizedFragmentIsCutWithAnEllipsis()
    {
        BaseClass xclass = new BaseClass();
        add(xclass, field(new StringClass(), "big", "x".repeat(500), 1));

        String rendered = MCPSchemaText.render(xclass, this.context);

        // A single crafted fragment (here a pretty name) is capped, so it cannot dominate its line.
        assertTrue(rendered.contains("\"" + "x".repeat(200) + "…\""), rendered);
        assertFalse(rendered.contains("x".repeat(201)), rendered);
    }

    @Test
    void definesNoFieldsDetectsOnlyATrulyEmptyClass()
    {
        BaseClass empty = new BaseClass();
        assertTrue(MCPSchemaText.definesNoFields(empty));

        BaseClass withEnabled = new BaseClass();
        add(withEnabled, field(new StringClass(), "one", null, 1));
        assertFalse(MCPSchemaText.definesNoFields(withEnabled));

        // A class whose every field is disabled still IS a class: it must describe, not read as a non-class.
        BaseClass withDisabledOnly = new BaseClass();
        StringClass off = field(new StringClass(), "off", null, 1);
        off.setDisabled(true);
        add(withDisabledOnly, off);
        assertFalse(MCPSchemaText.definesNoFields(withDisabledOnly));
    }

    @Test
    void disabledOnlyClassRendersAnEmptyFieldsBlockAndTheDisabledLine()
    {
        BaseClass xclass = new BaseClass();
        StringClass off = field(new StringClass(), "off", null, 1);
        off.setDisabled(true);
        add(xclass, off);

        assertEquals("FIELDS (display order)\n\nDISABLED FIELDS: off",
            MCPSchemaText.render(xclass, this.context));
    }
}
