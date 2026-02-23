#!/usr/bin/env bb

(require '[babashka.http-client :as client])
(require '[cheshire.core :as json])
(require '[clojure.java.io :as io])
(require '[clojure.edn :as edn])
(require '[clojure.string :as str])

;; --- Funções de Autenticação (padrão Google API) ---

(defn get-token []
  (let [token-file (io/file "token.json")]
    (if (.exists token-file)
      (json/parse-string (slurp token-file) true)
      (do (println "Erro: token.json não encontrado.") (System/exit 1)))))

(defn refresh-token [client-id client-secret refresh-token]
  (let [response (client/post "https://oauth2.googleapis.com/token"
                              {:form-params {:client_id client-id
                                             :client_secret client-secret
                                             :refresh_token refresh-token
                                             :grant_type "refresh_token"}})]
    (if (= 200 (:status response))
      (json/parse-string (:body response) true)
      (do (println "Erro ao atualizar token:" (:body response)) (System/exit 1)))))

(defn save-token [token]
  (spit "token.json" (json/generate-string token)))

(defn get-service [client-id client-secret]
  (let [token (get-token)]
    (if (or (not (:access_token token)) (<= (get token :expires_in 0) 60))
      (let [new-token (refresh-token client-id client-secret (:refresh_token token))]
        (let [merged-token (merge token new-token)]
          (save-token merged-token)
          merged-token))
      token)))

;; --- Funções de Envio de E-mail ---

(defn encode-base64url [s]
  (-> (java.util.Base64/getUrlEncoder)
      (.encodeToString (.getBytes s "UTF-8"))
      (str/replace #"\n|\r" "")))

(defn send-gmail [service to subject body]
  (let [;; Encodar o assunto para evitar problemas de acentuação (MIME encoding)
        encoded-subject (str "=?utf-8?B?" (encode-base64url subject) "?=")
        raw-message (str "To: " to "\n"
                         "Subject: " encoded-subject "\n"
                         "MIME-Version: 1.0\n" ;; Adicionado para melhor compatibilidade
                         "Content-Type: text/plain; charset=utf-8\n"
                         "Content-Transfer-Encoding: 7bit\n\n" ;; O corpo vai em UTF-8 puro
                         body)
        payload {:raw (encode-base64url raw-message)}]
    (try
      (let [response (client/post "https://gmail.googleapis.com/gmail/v1/users/me/messages/send"
                                  {:headers {"Authorization" (str "Bearer " (:access_token service))
                                             "Content-Type" "application/json"}
                                   :body (json/generate-string payload)})]
        (if (or (= 200 (:status response)) (= 201 (:status response)))
          true
          (do (println "Falha ao enviar e-mail para" to ":" (:body response)) false)))
      (catch Exception e
        (println "Erro na API do Gmail para" to ":" (.getMessage e))
        false))))

;; --- Função Principal ---

(defn main [& args]
  (if (not= (count args) 3)
    (do (println "Uso: bb send_emails.bb <links-edn-file> <client-secrets-file> <event-name>")
        (System/exit 1)))

  (let [links-file (nth args 0)
        client-secrets (json/parse-string (slurp (nth args 1)) true)
        event-name (nth args 2)

        creds (or (:installed client-secrets) (:web client-secrets))
        service (get-service (:client_id creds) (:client_secret creds))

        data (edn/read-string (slurp links-file))]

    (if (empty? data)
      (println "Nenhum link encontrado no arquivo" links-file)
      (do
        (println (str "Iniciando disparos de e-mail para o evento: " event-name "\n"))
        (doseq [[folder-name info] data]
          (let [emails (:emails info)
                link (:link info)]
            (doseq [email emails]
              (println "Enviando para:" email " (Pasta: " folder-name ")...")
              (let [subject (str "[" event-name "] Link para envio de arquivos")
                    body (str "Olá,\n\n"
                              "Você foi cadastrado no " event-name ".\n"
                              "Para enviar e gerenciar seus arquivos (apresentações, handouts, textos etc.) na sua pasta pessoal '"
                              folder-name "', utilize o link abaixo:\n\n" link "\n\n"
                              "Atenção: Este é um link de acesso direto para escrita na pasta. Não o compartilhe.\n\n")]
                (if (send-gmail service email subject body)
                  (println "✓ E-mail enviado com sucesso.")
                  (println "✗ Falha no envio."))))))
        (println "\nProcesso de e-mails finalizado.")))))

(apply main *command-line-args*)
