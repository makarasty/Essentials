## Translating for Essentials
Translations live in [`Essential/src/main/resources/bundles/`](https://github.com/makarasty/Essentials/tree/main/Essential/src/main/resources/bundles),
in three folders:

| Folder          | English file (the base)  | What it holds                         |
|:----------------|:-------------------------|:--------------------------------------|
| `common/`       | `bundle.properties`      | Plugin messages and command help      |
| `achievements/` | `bundle.properties`      | Achievement names and descriptions    |
| `web/`          | `web.properties`         | Web service messages                  |

Languages translated today: `ja`, `ko`, `uk`, `zh` (English is the base file, there is no `_en` file).

### Adding a language
1. In each folder, copy the English file to `<name>_<code>.properties`, for example `common/bundle_de.properties`
   and `web/web_de.properties`. Use the language code the Mindustry client reports (`de`, `pt_BR`, ...).
2. Translate the values, keep the keys. Save as UTF-8.
3. A key you leave out falls back to English. Do not copy an English line into your file to fill the gap.
4. Build the plugin (see [Building](README.md#building)) and put `Essential/build/libs/Essential-all.jar` in
   `<server>/config/mods`. The bundles are read from the jar only; a file dropped into the server's config
   folder is ignored. `/lang` lists the new language by itself.
5. Open a pull request against [makarasty/Essentials](https://github.com/makarasty/Essentials).

To fix an existing translation, edit its file the same way.

### Useful Information
* When you see text surrounded by square brackets, such as ``[RED]``, ``[]`` or ``[accent]``, this indicates a color code. Don't translate it.
* ``{0}``, ``{1}`` are arguments filled in when the text is displayed. Keep them, in any order your language needs.
* Write an apostrophe as ``''`` (two single quotes). A single ``'`` is dropped and stops the ``{0}`` after it from being filled in.
* ``\n`` means "new line". If you want to split text into multiple lines, use ``\n`` to do it.
