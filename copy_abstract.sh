#!/bin/sh

pandoc -f docx -t markdown --wrap=none "${1}" | \
    ./prep_abstract.bb | \
    sed -E '/^(title|name_first|name_last):/!s/([^{]|[^^])([A-Z]+)([^a-zá-üÀ-ÖÙ-ÿ’}])/\1\\textsclowercase{\2}\3/g' | \
    pbcopy

