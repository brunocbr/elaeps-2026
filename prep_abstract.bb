#!/usr/bin/env bb

(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def api-key (System/getenv "GEMINI_API_KEY"))

(if (str/blank? api-key)
  (do (binding [*out* *err*] (println "Error: GEMINI_API_KEY is not set."))
      (System/exit 1)))

(def markdown-input 
  (if (first *command-line-args*)
    (slurp (first *command-line-args*))
    (slurp *in*)))

(def system-prompt
  "You are a specialist in academic metadata extraction.
   TASK:
   1. Extract the title, author's first and last name, email, institution, keywords, and the abstract body.
   2. If a specific field (like email or keywords) is NOT present in the text, return an empty string or empty list.
   3. DO NOT invent keywords. Only extract them if they are explicitly listed in the document.
   4. DO NOT translate. Maintain the original language of the text.
   5. Return ONLY a valid JSON object.

   JSON structure:
   {
     \"title\": \"...\",
     \"first_name\": \"...\",
     \"last_name\": \"...\",
     \"email\": \"...\",
     \"institution\": \"...\",
     \"keywords\": [\"...\", \"...\"],
     \"body\": \"...\"
   }")

(defn call-gemini [content]
  (let [url (str "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=" api-key)
        payload {"contents" [{"parts" [{"text" (str system-prompt "\n\nDOCUMENT TO ANALYZE:\n" content)}]}]}
        response (http/post url {:body (json/generate-string payload)
                                 :headers {"Content-Type" "application/json"}
                                 :throw false})]
    (if (= (:status response) 200)
      (let [body (json/parse-string (:body response) true)
            text-content (-> body :candidates first :content :parts first :text)
            clean-json (str/replace text-content #"(?s)```(?:json)?\n?|\n?```" "")]
        (json/parse-string clean-json true))
      (throw (Exception. (str "API error " (:status response) ": " (:body response)))))))

(try
  (let [data (call-gemini markdown-input)]
    (println "---")
    (println "title:" (str "\"" (:title data) "\""))
    (println "name_first:" (:first_name data))
    (println "name_last:" (:last_name data))
    (println "email:" (:email data))
    (println "institution:" (str "\"" (:institution data) "\""))
    (println "keywords:" (str/join ", " (:keywords data)))
    (println "---")
    (println "\n" (:body data)))
  (catch Exception e
    (binding [*out* *err*]
      (println "Error processing document:" (.getMessage e))
      (System/exit 1))))
