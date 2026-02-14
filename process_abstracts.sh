#!/bin/bash

# Verifica se foram passados arquivos
if [ $# -eq 0 ]; then
    echo "Usage: $0 file1.docx [file2.docx ...]"
    exit 1
fi

# Loop pelos arquivos fornecidos
for file in "$@"; do
    if [[ "$file" == *.docx ]]; then
        # Define o nome de saída trocando .docx por .md
        output_file="${file%.docx}.md"

        echo "Processing: $file -> $output_file"

        # Executa o pipeline (Pandoc -> Babashka -> Sed) e salva no arquivo
        pandoc -f docx -t markdown --wrap=none "$file" | \
            ./prep_abstract.bb | \
            sed -E '/^(title|name_first|name_last):/!s/([^{]|[^^])([A-Z]+)([^a-zá-üÀ-ÖÙ-ÿ’}])/\1\\textsclowercase{\2}\3/g' \
                > "$output_file"

        echo "Done!"
    else
        echo "Skipping '$file': Not a .docx file."
    fi
done

