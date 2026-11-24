# Local data previews

Open a supported local CSV, TSV, TAB, FASTA, FASTQ or VCF file to see its saved records in the **Preview**
tab. The IDE's ordinary **Text** tab remains available for editing and uses the
platform's large file editor when applicable. The preview neither loads a source
document nor changes its bytes.

The first CSV or TSV record supplies column headings. CSV fields can contain
commas or line breaks inside quotes, and doubled quotes represent a literal
quote. TSV preserves quotes literally and splits on tabs. Values remain
strings, so leading zeros, precision and empty fields are preserved. Select a
row to see its values and the original byte offset.

**Next page** reads at the next record boundary. **Previous page** retains up to
64 page positions. **Stop reading** cancels the current request. Use
**Reload preview** after a saved edit or external file change. When the native
editor has unsaved changes, save them before reading the preview.

Each page holds up to 200 records. A CSV/TSV or VCF field is limited to 16 KiB, a record
to 128 KiB, and a record to 256 columns. A page can end early to bound its
retained text, and selected-row details are clipped at 32 Ki UTF-16 code units.
Oversized or malformed input produces a message with its source byte offset.
The reader accepts UTF-8, including an optional byte order mark, with LF, CRLF or
CR line endings. A previously loaded page stays visible if the next read fails.

## FASTA sequences

FASTA previews open local .fa, .fasta, .fna, .faa and .fas files. Each record
shows its identifier, description, complete sequence length and the first 512
sequence characters. The reader streams wrapped and unwrapped sequence lines
and excludes spaces and tabs from the displayed sequence and length. Headers
can contain UTF-8 descriptions. Sequence characters are printable ASCII.

A page request reads up to 64 MiB of source data. If a single record exceeds
that limit, the preview reports its position and Text remains available.

## FASTQ reads

FASTQ previews open local .fq and .fastq files. Each record shows its
identifier, description, sequence length and the first 512 characters of
the sequence and quality. Both fields can span multiple lines. Quality is
shown as raw printable ASCII, including leading @ or + characters. The
reader compares full sequence and quality lengths before displaying the record.

## VCF variants

Open a local .vcf file to see the original columns and record values. Each row
keeps POS as written and retains INFO, FORMAT and sample strings. The
**Metadata / headers** control shows the original double-hash lines. The pane
retains up to 256 lines and 64 Ki UTF-16 code units, and points to Text when
further lines are available there.

The preview checks the column header, field counts and the shape of POS and
reports the location of a malformed row. Use Text to inspect or edit the full
file.
