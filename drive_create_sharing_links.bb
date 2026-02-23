#!/usr/bin/env bb

(require '[babashka.http-client :as client])
(require '[cheshire.core :as json])
(require '[clojure.java.io :as io])
(require '[clojure.edn :as edn])

;; --- Funções de Autenticação e Token ---

(defn get-token []
  (let [token-file (io/file "token.json")]
    (if (.exists token-file)
      (json/parse-string (slurp token-file) true)
      (do
        (println "Erro: arquivo token.json não encontrado. Rode o script de autorização primeiro.")
        (System/exit 1)))))

(defn refresh-token [client-id client-secret refresh-token]
  (let [response (client/post "https://oauth2.googleapis.com/token"
                              {:form-params {:client_id client-id
                                             :client_secret client-secret
                                             :refresh_token refresh-token
                                             :grant_type "refresh_token"}})]
    (if (= 200 (:status response))
      (json/parse-string (:body response) true)
      (do
        (println "Erro ao atualizar token:" (:body response))
        (System/exit 1)))))

(defn save-token [token]
  (spit "token.json" (json/generate-string token)))

(defn get-service [client-id client-secret]
  (let [token (get-token)]
    ;; Atualiza o token se estiver expirado ou perto de expirar (60 segundos de margem)
    (if (or (not (:access_token token)) (<= (get token :expires_in 0) 60))
      (let [new-token (refresh-token client-id client-secret (:refresh_token token))]
        (let [merged-token (merge token new-token)]
          (save-token merged-token)
          merged-token))
      token)))

;; --- Funções do Google Drive ---

(defn list-subfolders [service folder-id]
  (let [response (client/get "https://www.googleapis.com/drive/v3/files"
                             {:query-params {:q (str "'" folder-id "' in parents and trashed = false and mimeType = 'application/vnd.google-apps.folder'")
                                             :fields "nextPageToken, files(id, name)"}
                              :headers {"Authorization" (str "Bearer " (:access_token service))}})]
    (if (= 200 (:status response))
      (json/parse-string (:body response) true)
      (do 
        (println "Erro ao listar subpastas:" (:body response)) 
        (System/exit 1)))))

(defn enable-link-sharing [service file-id]
  (try
    ;; 1. Altera a permissão para 'anyone' (qualquer pessoa) como 'writer' (editor)
    (client/post (str "https://www.googleapis.com/drive/v3/files/" file-id "/permissions")
                 {:headers {"Authorization" (str "Bearer " (:access_token service))
                            "Content-Type" "application/json"}
                  :body (json/generate-string {:role "writer"
                                               :type "anyone"})})
    
    ;; 2. Obtém o link web da pasta (webViewLink)
    (let [response (client/get (str "https://www.googleapis.com/drive/v3/files/" file-id)
                               {:headers {"Authorization" (str "Bearer " (:access_token service))}
                                :query-params {:fields "webViewLink"}})
          body (json/parse-string (:body response) true)]
      (:webViewLink body))
    
    (catch Exception e
      (println "Falha ao processar pasta" file-id ":" (.getMessage e))
      nil)))

;; --- Função Principal ---

(defn main [& args]
  (if (not= (count args) 3)
    (do
      (println "Uso: bb drive_invitations.bb <parent-folder-id> <client-secrets-json> <output-edn-path>")
      (System/exit 1)))

  (let [[parent-id secrets-path output-path] args
        client-secrets (json/parse-string (slurp secrets-path) true)
        ;; Suporta formatos 'web' ou 'installed' do arquivo de credenciais do Google
        creds (or (:installed client-secrets) (:web client-secrets))
        
        ;; Lê o mapa de e-mails vindo do STDIN (output do compile_abstracts.bb)
        email-map (edn/read-string (slurp *in*))
        
        service (get-service (:client_id creds) (:client_secret creds))
        subfolders (list-subfolders service parent-id)
        
        ;; Átomo para armazenar o mapeamento final: FolderName -> {:emails [...] :link "..."}
        results (atom {})]

    (println "--- Iniciando Geração de Links de Compartilhamento ---")

    (doseq [subfolder (:files subfolders)]
      (let [folder-name (:name subfolder)
            file-id (:id subfolder)
            emails (get email-map folder-name)]
        (if (seq emails)
          (do
            (print (str "Processando [" folder-name "]... "))
            (if-let [link (enable-link-sharing service file-id)]
              (do
                (swap! results assoc folder-name {:emails emails :link link})
                (println "✓ Link Ativado"))
              (println "✗ Erro")))
          (println "! Ninguém mapeado para a pasta:" folder-name))))

    ;; Garante que o diretório de destino existe (ex: _output)
    (io/make-parents output-path)
    ;; Salva os resultados em formato EDN para o próximo script ler
    (spit output-path (pr-str @results))
    
    (println "\nConcluído!")
    (println "Mapeamento salvo em:" output-path)
    (println "Total de pastas processadas:" (count @results))))

(apply main *command-line-args*)
