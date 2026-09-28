package com.bookrio.core.parse

/**
 * Semantisk EPUB-lesestruktur: kapitler drives av nav/NCX-grenser (fallback:
 * overskrifter/filnavn-semantikk). Ikke-TOC-ryggradsfiler (omslag, tittelside,
 * opphavsrett, innholdsfortegnelse …) grupperes i én "Frontmatter"-seksjon og
 * presenteres ALDRI som falske "Kapittel N".
 */
enum class SectionKind { COVER, TITLE_PAGE, COPYRIGHT, DEDICATION, CONTENTS, FRONT_MATTER, BODY, BACK_MATTER }

object EpubSectionBuilder {

    private val FRONT_KINDS = setOf(
        SectionKind.COVER, SectionKind.TITLE_PAGE, SectionKind.COPYRIGHT,
        SectionKind.DEDICATION, SectionKind.CONTENTS, SectionKind.FRONT_MATTER,
    )

    /** Normaliser href for nav/NCX-matching. */
    private fun fileKey(href: String): String =
        href.substringBefore('#').replace('\\', '/').trimStart('/').lowercase()

    /** Filnavn-basert klassifisering for ryggradsfiler som ikke er TOC-mål. */
    private fun semanticKind(href: String): SectionKind? {
        val base = href.substringAfterLast('/').substringBeforeLast('.')
            .lowercase().replace(Regex("[-_ ]+"), "")
        return when (base) {
            "cover", "coverpage", "omslag" -> SectionKind.COVER
            "titlepage", "title_page", "title", "tittelside" -> SectionKind.TITLE_PAGE
            "copyright", "copyrightpage", "colophon", "imprint", "opphavsrett" -> SectionKind.COPYRIGHT
            "dedication" -> SectionKind.DEDICATION
            "nav", "toc", "contents", "tocncx", "ncx", "innholdsfortegnelse", "content" -> SectionKind.CONTENTS
            else -> null
        }
    }

    private fun semanticTitle(kind: SectionKind): String = when (kind) {
        SectionKind.COVER -> "Omslag"
        SectionKind.TITLE_PAGE -> "Tittelside"
        SectionKind.COPYRIGHT -> "Opphavsrett"
        SectionKind.DEDICATION -> "Dedikasjon"
        SectionKind.CONTENTS -> "Innholdsfortegnelse"
        SectionKind.FRONT_MATTER -> "Frontmatter"
        SectionKind.BACK_MATTER -> "Etterord"
        SectionKind.BODY -> "Innhold"
    }

    /** Første ikke-tomme h1–h3-overskrift i innholdet (fallback-tittel). */
    internal fun headingTitle(html: String?): String? {
        if (html == null) return null
        val candidates = Regex("<h[1-3][^>]*>(.*?)</h[1-3]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html)
            .map { it.groupValues[1].replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() && it.length < 80 }
            .toList()
        if (candidates.isEmpty()) return null
        val generic = Regex("^(chapter|kapittel|del|part)\\s*([0-9ivxlc]+)\\b", RegexOption.IGNORE_CASE)
        val first = candidates.first()
        return if (generic.containsMatchIn(first)) {
            candidates.firstOrNull { !generic.containsMatchIn(it) } ?: first
        } else {
            first
        }
    }

    private data class Section(
        val kind: SectionKind,
        val inToc: Boolean,
        var title: String?,
        val members: MutableList<String>,
    )

    /**
     * Bygger leserens seksjonsstruktur:
     *  - nav/NCX-mål starter BODY-seksjoner med TOC-tittel (inToc = true)
     *  - ryggradsfiler som ikke er TOC-mål føyes til den løpende seksjonen
     *  - filer før første TOC-grense grupperes i én Frontmatter-seksjon (inToc = false)
     *  - uten nav/NCX: front-namede filer grupperes; resten blir egne kapitler
     *    med h1–h3-overskrift som tittel (fallback "Kapittel N")
     *
     * @param spineHrefs  manifest-href i spine-rekkefølge
     * @param navPoints   ordnede nav/NCX-punkter (kan være tom)
     * @param htmlFor     rensede HTML-innhold per spine-href
     */
    fun build(
        spineHrefs: List<String>,
        navPoints: List<NavPoint>,
        htmlFor: (String) -> String?
    ): List<ParsedChapter> {
        val navTitleBySpine = mutableMapOf<Int, String>()
        for (np in navPoints) {
            val spineIdx = spineHrefs.indexOfFirst { href ->
                val h = fileKey(href)
                val s = fileKey(np.srcHref)
                s.isNotEmpty() && (s == h || s.endsWith("/$h") || h.endsWith("/$s"))
            }
            if (spineIdx >= 0 && !navTitleBySpine.containsKey(spineIdx)) {
                navTitleBySpine[spineIdx] = np.title.trim()
            }
        }
        fun navTitleForSpine(i: Int): String? = navTitleBySpine[i]?.takeIf { it.isNotBlank() }
        val hasNav = navTitleBySpine.isNotEmpty()

        val out = mutableListOf<ParsedChapter>()
        var cumulative = 0

        fun emit(section: Section, cumulative: Int): Int {
            val html = section.members.joinToString("\n") { m -> htmlFor(m) ?: "" }
            if (html.isBlank()) return cumulative
            val full = "<section>$html</section>"
            val bytes = full.toByteArray().size
            out.add(
                ParsedChapter(
                    index = out.size,
                    title = section.title ?: semanticTitle(section.kind),
                    htmlContent = full,
                    startByte = cumulative,
                    byteLength = bytes,
                    inToc = section.inToc,
                    kind = section.kind,
                )
            )
            return cumulative + bytes
        }

        var front: Section? = null       // buffer: ryggradsfiler før første TOC-grense
        var body: Section? = null        // åpen BODY-seksjon som medlemmer føyes til
        var bodyPhase = false

        fun flushFront() {
            front?.let { f ->
                val kind = if (f.members.size > 1) SectionKind.FRONT_MATTER else f.kind
                cumulative = emit(f.copy(kind = kind), cumulative)
                front = null
            }
        }

        fun flushBody() {
            body?.let { b ->
                val title = b.title ?: headingTitle(
                    b.members.joinToString("\n") { m -> htmlFor(m) ?: "" }
                ) ?: "Kapittel ${out.size + 1}"
                cumulative = emit(b.copy(title = title), cumulative)
                body = null
            }
        }

        spineHrefs.forEachIndexed { i, href ->
            val navTitle = navTitleForSpine(i)
            if (navTitle != null) {
                // 1) TOC-grense: avslutt front-buffer og løpende body-seksjon,
                //    start ny BODY-seksjon med TOC-tittel.
                flushFront()
                flushBody()
                bodyPhase = true
                body = Section(SectionKind.BODY, inToc = true, title = navTitle, members = mutableListOf(href))
                return@forEachIndexed
            }

            val sk = semanticKind(href)

            // 2) Før første TOC-grense (kun når boken HAR nav): alt samles i én
            //    Frontmatter-seksjon.
            if (hasNav && !bodyPhase) {
                val kind = sk ?: SectionKind.FRONT_MATTER
                val f = front ?: Section(kind, inToc = false, title = semanticTitle(kind), members = mutableListOf())
                    .also { front = it }
                if (f.members.isEmpty() && sk != null && FRONT_KINDS.contains(sk)) {
                    f.title = semanticTitle(sk)
                }
                f.members.add(href)
                return@forEachIndexed
            }

            // 3) Ingen nav/NCX i det hele tatt: front-namede filer grupperes,
            //    resten blir egne kapitler med h1–h3-overskrift som tittel.
            if (!hasNav && sk != null && FRONT_KINDS.contains(sk)) {
                flushBody()
                val f = front ?: Section(sk, inToc = false, title = semanticTitle(sk), members = mutableListOf())
                    .also { front = it }
                f.members.add(href)
                return@forEachIndexed
            }

            // 4) Body-fase: filen føyes til den løpende BODY-seksjonen.
            body?.let { b -> b.members.add(href) }
                ?: run {
                    val kind = sk ?: SectionKind.BODY
                    val title = sk?.let { semanticTitle(it) } ?: headingTitle(htmlFor(href))
                    body = Section(kind, inToc = true, title = title, members = mutableListOf(href))
                }
        }

        flushBody()
        flushFront()
        return out
    }
}
