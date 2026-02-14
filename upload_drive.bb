#!/usr/bin/env bb

(require '[cheshire.core :as json]
         '[babashka.http-client :as http]
         '[clojure.java.io :as io])

(import [java.io ByteArrayOutputStream])

(def token-file "token.json")
(def env (into {} (System/getenv)))

(defn get-access-token []
  (if (.exists (io/file token-file))
    (-> (slurp token-file)
        (json/parse-string true)
        :access_token)
    (throw (Exception. "Token not found. Run 'make google_authorization' first."))))

(defn find-existing-file [name folder-id token]
  (let [query (str "name = '" name "' and '" folder-id "' in parents and trashed = false")
        url (str "https://www.googleapis.com/drive/v3/files?q=" 
                 (java.net.URLEncoder/encode query "UTF-8"))
        response (http/get url {:headers {"Authorization" (str "Bearer " token)}})
        files (-> (:body response) (json/parse-string true) :files)]
    (first files)))

(defn upload-pdf [local-path target-name folder-id]
  (let [token (get-access-token)
        local-file (io/file local-path)
        existing-file (find-existing-file target-name folder-id token)
        file-id (:id existing-file)
        base-url "https://www.googleapis.com/upload/drive/v3/files"]

    (if file-id
      (println (str "Target file found (ID: " file-id "). Updating content..."))
      (println (str "Target file not found. Creating new file: " target-name)))

    (try
      (let [response 
            (if file-id
              ;; 1. Update (PATCH) - Simple binary upload (uploadType=media)
              (http/patch (str base-url "/" file-id "?uploadType=media")
                {:headers {"Authorization" (str "Bearer " token)
                           "Content-Type" "application/pdf"}
                 :body local-file})
              
              ;; 2. Create (POST) - Multipart upload (metadata + binary)
              (let [boundary "bb_drive_upload_boundary"
                    metadata (json/generate-string {:name target-name :parents [folder-id]})
                    out (ByteArrayOutputStream.)]
                ;; Escreve os bytes dos cabeçalhos e metadados
                (.write out (.getBytes (str "--" boundary "\r\n"
                                            "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                                            metadata "\r\n"
                                            "--" boundary "\r\n"
                                            "Content-Type: application/pdf\r\n\r\n") "UTF-8"))
                ;; Copia os bytes brutos do arquivo PDF original
                (io/copy local-file out)
                ;; Fecha a boundary
                (.write out (.getBytes (str "\r\n--" boundary "--") "UTF-8"))
                
                (http/post (str base-url "?uploadType=multipart")
                  {:headers {"Authorization" (str "Bearer " token)
                             "Content-Type" (str "multipart/related; boundary=" boundary)}
                   :body (.toByteArray out)})))]
        
        (if (contains? #{200 204} (:status response))
          (let [final-id (or file-id (-> (:body response) (json/parse-string true) :id))]
            (println "\n✅ Upload successful!")
            (println "🔗 Permanent View Link:")
            (println (str "   https://drive.google.com/file/d/" final-id "/view"))
            (println "📥 Direct Download Link:")
            (println (str "   https://drive.google.com/uc?export=download&id=" final-id "\n")))
          (println "❌ Upload failed:" (:body response))))
      (catch Exception e
        (println "❌ Error during upload:" (.getMessage e))))))

;; Command line execution logic
(let [[local-path] *command-line-args*
      target-name (get env "PDF_TARGET_NAME" "Book_of_Abstracts.pdf")
      folder-id (get env "GOOGLE_DRIVE_FOLDER_ID")]
  (cond
    (not (and local-path (.exists (io/file local-path))))
    (println "Usage: bb upload_drive.bb <path-to-local-file>")
    
    (not folder-id)
    (println "Error: GOOGLE_DRIVE_FOLDER_ID not set in .env")
    
    :else
    (upload-pdf local-path target-name folder-id)))
