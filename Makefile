include .env

OUTPUT_DIR=_output
TEMPLATE_DIR=templates
ABSTRACTS_DIR=abstracts
IMAGE_DIR=images
DATA_DIR=data
GOOGLE_CREDENTIALS=credentials.json

# Lista todos os arquivos markdown dentro da pasta de abstracts
ABSTRACTS_FILES := $(wildcard $(ABSTRACTS_DIR)/*.md)
DATA_FILES := $(wildcard $(DATA_DIR)/*)

all: create_drive_subfolders generate_drive_links book

# Só roda se o script, algum .md, os templates ou os dados mudarem
$(OUTPUT_DIR)/abstracts.tex: compile_abstracts.bb $(ABSTRACTS_FILES) $(TEMPLATE_DIR)/book-of-abstracts.latex $(DATA_FILES)
	@mkdir -p $(OUTPUT_DIR)
	bb compile_abstracts.bb --path $(ABSTRACTS_DIR) --format latex > $@

$(OUTPUT_DIR)/book-of-abstracts.pdf: $(OUTPUT_DIR)/abstracts.tex $(TEMPLATE_DIR)/book-of-abstracts.latex $(IMAGE_DIR)/*
	cd $(OUTPUT_DIR) && \
	xelatex ../$(TEMPLATE_DIR)/book-of-abstracts.latex && \
	while grep -q 'Rerun to get' book-of-abstracts.log || grep -q 'LaTeX Warning: Label(s) may have changed' book-of-abstracts.log; do \
		xelatex ../$(TEMPLATE_DIR)/book-of-abstracts.latex; \
	done

book: $(OUTPUT_DIR)/book-of-abstracts.pdf

# Versão otimizada para o Word também
$(OUTPUT_DIR)/program.docx: compile_abstracts.bb $(ABSTRACTS_FILES) $(TEMPLATE_DIR)/program.docx $(DATA_DIR)/*.yml
	bb compile_abstracts.bb --path $(ABSTRACTS_DIR) --format program | \
	pandoc -f markdown -t docx --reference-doc=$(TEMPLATE_DIR)/program.docx \
		-o $@

program: $(OUTPUT_DIR)/program.docx

clean:
	rm -f $(OUTPUT_DIR)/abstracts.tex $(OUTPUT_DIR)/book-of-abstracts.pdf \
		$(OUTPUT_DIR)/*.aux $(OUTPUT_DIR)/*.log $(OUTPUT_DIR)/*.out \
		$(OUTPUT_DIR)/program.docx


google_authorization:
	bb google_auth.bb $(GOOGLE_CREDENTIALS)

deploy_pdf: $(OUTPUT_DIR)/book-of-abstracts.pdf google_authorization
	bb upload_drive.bb $(OUTPUT_DIR)/book-of-abstracts.pdf

create_drive_subfolders: google_authorization
	bb compile_abstracts.bb --path $(ABSTRACTS_DIR) --format authors | \
	bb create_drive_subfolders.bb $(GOOGLE_DRIVE_FOLDER_ID) $(GOOGLE_CREDENTIALS)

generate_drive_links: google_authorization
	bb generate_drive_links.bb $(GOOGLE_DRIVE_FOLDER_ID) $(GOOGLE_CREDENTIALS) \
		>$(DATA_DIR)/google_drive.edn

generate_sharing_links: google_authorization
	@mkdir -p $(OUTPUT_DIR)
	bb compile_abstracts.bb --path $(ABSTRACTS_DIR) --format emails | \
	bb drive_create_sharing_links.bb $(GOOGLE_DRIVE_FOLDER_ID) $(GOOGLE_CREDENTIALS) $(DATA_DIR)/sharing_links.edn

notify_authors: $(DATA_DIR)/sharing_links.edn
	bb send_emails.bb $(DATA_DIR)/sharing_links.edn $(GOOGLE_CREDENTIALS) $(EVENT_NAME)

clean_drive_links:
	rm -f $(DATA_DIR)/google_drive.edn

update: generate_drive_links book deploy_pdf

