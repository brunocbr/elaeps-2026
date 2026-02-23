# Book of Abstracts Generator

This repository contains the tools to generate the Book of Abstracts for the conference "II Encontro Latino-Americano de Estudos Pré-Socráticos", and manage Google Drive presentation folders.

## Requirements

To generate the Book of Abstracts, you will need the following tools installed on your system:

- [Babashka](https://github.com/babashka/babashka): A fast, expressive, and feature-rich shell/scripting environment for Clojure.
- [Pandoc](https://pandoc.org/): A universal document converter.
- LaTeX with XeLaTeX: For typesetting the PDF document.
- **Google Gemini API Key**: Required for AI-powered metadata extraction.

## Setup

1. Clone this repository to your local machine.
2. Create a `.env` file in the root of the repository with the information below.

### Google Drive Links

To generate the links to subfolders on a shared Drive folder, one per author name, ensure you have the following:

1. **Google Cloud Project**: Create a Google Cloud project, enable the Google Drive API, and create OAuth2 credentials.
2. **Generate `credentials.json`**:
   - Go to the [Google Cloud Console](https://console.cloud.google.com/).
   - Create a new project or select an existing project.
   - Navigate to the **API & Services > Credentials** page.
   - Click on **Create Credentials** and select **OAuth client ID**.
   - Configure the OAuth consent screen if prompted.
   - Choose **Desktop app** as the application type and click **Create**.
   - Ensure you enable both **Google Drive API** and **Gmail API**.
   - Download the `credentials.json` file and save it in the same directory as the script.
 3. Set `GOOGLE_DRIVE_FOLDER_ID` in your `.env` file to the corresponding folder id.

### Example `.env` file

```sh
# Target PDF File Name
export PDF_TARGET_NAME=Book_of_Abstracts.pdf

# Google Drive Folder ID
export GOOGLE_DRIVE_FOLDER_ID=<the folder id>

# Gemini AI API Key (Get it at https://aistudio.google.com/)
export GEMINI_API_KEY=your_key_here

# Event Name for Email Communications
export EVENT_NAME="II Encontro Latino-Americano de Estudos Pré-Socráticos"
```

## Processing New Abstracts

We use an AI-assisted workflow to convert incoming `.docx` abstracts into structured Markdown files.

### The Pipeline

1. **`prep_abstract.bb`**: Babashka script that uses Google Gemini to extract title, author, email, institution, and keywords.
2. **`copy_abstract.sh`**: Utility shell script that:
   - Converts Word to Markdown via Pandoc.
   - Pipes content through `prep_abstract.bb`.
   - Applies a `sed` filter for LaTeX `\textsclowercase{}`.
   - Copies the result to the **clipboard**.
3. **`process_abstracts.sh`**: A batch processing script that:
   - Accepts one or multiple `.docx` files as arguments.
   - Runs the full pipeline (Pandoc, Gemini extraction, and LaTeX formatting).
   - Automatically saves the output into individual `.md` files with the same base name as the source.

### Usage

To process a new submission and get the formatted text ready to be pasted into the project:

```sh
./copy_abstract.sh path/to/author_submission.docx
```

The formatted content will be in your clipboard. You can then create a new .md file in the abstracts directory and paste the content.

### Batch Processing

To process multiple files at once and save them as `.md` files (instead of copying to clipboard):

```sh
./process_abstracts.sh abstracts/*.docx
```

This will create a corresponding `.md` file for each `.docx` file in the same directory.

## Usage (General)

To generate the Book of Abstracts in PDF format, use the following command:

```sh
make book
```

This command will compile the abstracts and generate the PDF file.

To upload the generated PDF to AWS S3, use the following command:

```sh
make deploy_pdf
```

This will upload the PDF to the specified Google Drive folder.

To create a Word Document with the Program:

```sh
make program
```

This will create `program.docx`, which can be used for conference.

Before operating with the Google API, you have to get authorization:

```sh
make google_authorization
```

This will create or refresh the token stored in `token.json`.

### Managing Author Folders and Notifications

The workflow for managing author presentation folders follows these steps:

1. **Authorize Google Access**:
   Generate or refresh your local `token.json` (requires Drive and Gmail scopes).

```sh
make google_authorization
```

2. **Create Subfolders**:
Create individual folders for each author on Google Drive.

```sh
make create_drive_subfolders
```


3. **Generate Sharing Links**:
This command enables "Link Sharing" (anyone with the link can edit) for all author folders and saves the links to `data/sharing_links.edn`. This method works for any email address (including institutional ones like .edu).

```sh
make generate_sharing_links
```

4. **Notify authors**

```sh
make notify_authors
```


5. Update the PDF with Google Drive information and deploy the PDF as needed

```sh
make update
```

This will update the database, generate the PDF and deploy it.

## Contributing

Contributions to this project are welcome. If you find any issues or have suggestions for improvements, please open an issue or submit a pull request.

---
Maintained by Bruno Conte
