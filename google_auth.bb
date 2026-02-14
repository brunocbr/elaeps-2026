#!/usr/bin/env bb

(require '[cheshire.core :as json]
         '[babashka.http-client :as http]
         '[clojure.java.shell :refer [sh]]
         '[org.httpkit.server :as server]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

(def port 8080)
(def redirect-uri (str "http://127.0.0.1:" port))
(def token-file "token.json")

;; Load credentials from your JSON file
(if-not (.exists (io/file "credentials.json"))
  (do (println "Error: credentials.json not found.") (System/exit 1)))

(def creds (json/parse-string (slurp "credentials.json") true))
(def client-id (get-in creds [:installed :client_id]))
(def client-secret (get-in creds [:installed :client_secret]))

(defn save-token [data]
  (let [content (if (string? data) data (json/generate-string data))]
    (spit token-file content)))

(defn is-token-valid? []
  (if-not (.exists (io/file token-file))
    false
    (try
      (let [token (-> (slurp token-file) (json/parse-string true) :access_token)
            ;; {:throw false} prevents the script from crashing on 400/401 errors
            resp (http/get (str "https://www.googleapis.com/oauth2/v3/tokeninfo?access_token=" token)
                           {:throw false})]
        (= 200 (:status resp)))
      (catch Exception _ false))))

(defn refresh-access-token [refresh-token]
  (println "Access token expired. Attempting refresh...")
  (try
    (let [resp (http/post "https://oauth2.googleapis.com/token"
                         {:form-params {:client_id client-id
                                        :client_secret client-secret
                                        :refresh_token refresh-token
                                        :grant_type "refresh_token"}
                          :throw false})]
      (if (= 200 (:status resp))
        (let [new-data (json/parse-string (:body resp) true)
              current (json/parse-string (slurp token-file) true)
              ;; Merge to keep the original refresh_token as Google doesn't always send it back on refresh
              updated (merge current new-data)]
          (save-token updated)
          (println "✅ Token refreshed successfully.")
          true)
        (do (println "❌ Refresh failed: " (:body resp)) false)))
    (catch Exception e
      (println "❌ Error during refresh:" (.getMessage e))
      false)))

;; --- Browser Auth Flow ---

(def promise-code (promise))

(defn extract-code [query-string]
  (->> (str/split (or query-string "") #"&")
       (map #(str/split % #"="))
       (filter #(= "code" (first %)))
       first
       second))

(defn callback-handler [req]
  (if-let [code (extract-code (:query-string req))]
    (do (deliver promise-code code)
        {:status 200 :body "Authentication successful! You can close this tab and return to the terminal."})
    {:status 400 :body "Authorization code not found in request."}))

(defn full-browser-auth []
  (let [stop-server (server/run-server callback-handler {:port port})
        auth-url (str "https://accounts.google.com/o/oauth2/v2/auth?"
                      "scope=https://www.googleapis.com/auth/drive.file"
                      "&response_type=code"
                      "&access_type=offline"
                      "&prompt=consent"
                      "&redirect_uri=" redirect-uri
                      "&client_id=" client-id)]

    (println "\nTarget folder requires new authorization.")
    (println "Opening browser...")

    ;; Opens browser (macOS 'open'). Use 'xdg-open' on Linux if needed.
    (sh "open" auth-url) 

    (let [code @promise-code
          resp (http/post "https://oauth2.googleapis.com/token"
                          {:form-params {:code code
                                         :client_id client-id
                                         :client_secret client-secret
                                         :redirect_uri redirect-uri
                                         :grant_type "authorization_code"}})]
      (save-token (:body resp))
      (println "✅ New token.json generated."))

    (stop-server)))

;; --- Main Execution ---

(let [token-data (when (.exists (io/file token-file)) 
                   (json/parse-string (slurp token-file) true))
      r-token (:refresh_token token-data)]
  (cond
    ;; 1. Check if existing token is still alive
    (is-token-valid?)
    (println "✅ Active token is valid. Skipping authentication.")

    ;; 2. If expired, try to use the refresh_token to get a new one silently
    (and r-token (refresh-access-token r-token))
    (println "Ready to go.")

    ;; 3. If everything else fails, go through the browser flow
    :else
    (full-browser-auth)))

(System/exit 0)
