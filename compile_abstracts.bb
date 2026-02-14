(require '[clojure.java.io :as io]
         '[selmer.parser :as selmer]
         '[clojure.string :as str]
         '[hiccup.core  :as h]
         '[babashka.tasks :as tasks]
         '[babashka.cli :as cli]
         '[babashka.fs :as fs]
         '[clojure.edn :as edn])

(def google-drive-data "data/google_drive.edn")

(defn inspect [x]
  (prn "+" x)
  x)

(defn read-yaml-header [file]
  (let [lines (-> file io/file slurp str/split-lines)]
    (->> lines
         (drop 1)
         (take-while #(not= "---" %))
         (str/join "\n")
         (yaml/parse-string))))

(defn read-body [file]
  (let [lines (-> file io/file slurp str/split-lines)]
    (->> lines
         (drop 1)
         (drop-while #(not= "---" %))
         (drop 1)
         (drop-while #(= "" %))
         (str/join "\n"))))

(defn obfuscate-email [email]
  (apply str (map #(format "&#x%x;" (int %)) email)))

(defn obfuscated-email-span [email]
  (h/html [:span {:class "obfuscated-email"
                  :data-email (obfuscate-email email)}
           "✉️"]))

(defn get-data [file]
  (->> (read-yaml-header file)
       (into {})))

(defn get-sessions []
  (get-data "data/sessions.yml"))

(defn get-drive []
  (if (fs/exists? google-drive-data)
    (assoc {} :drive
           (->> google-drive-data
                slurp
                edn/read-string
                (into {})))))

(defn render-markdown [file]
  (let [header (read-yaml-header file)
        body   (read-body file)
        index-name (:index_name header)
        author (str (if index-name
                      index-name
                      (:name_last header))
                    "!" (:name_first header))]
    {:author author
     :contents (selmer/render
                "#### {{author}}{% if institution %} ({{institution}}){% endif %}. {{title}}{% if email %} {{obfuscated-email|safe}}{% endif %}\n\n" ;; {{body}}\n\n
                {:title (:title header)
                 :author (str (:name_last header) ", " (:name_first header))
                 :email (:email header)
                 :obfuscated-email (obfuscated-email-span (:email header))
                 :institution (:institution header)
                 :body body})}))

(defn process-markdown [files]
  (->> files
       (map render-markdown)
       (sort-by :author)
       (map :contents)
       (str/join "\n\n")))

(defn render-program-entry [file]
  (let [header (read-yaml-header file)
        {:keys [id order]} (:session header)]
    {:contents (selmer/render
                "{{title}}  \n{{author}}{% if institution %}, {{institution}}{% endif %}"
                {:title (:title header)
                 :author (str (:name_first header) " " (:name_last header))
                 :institution (:institution header)})
     :session-id id
     :session-order order}))

(defn render-program-session [all-entries {:keys [id date time]}]
  (let [session-entries (->> all-entries
                             (filter #(= id (:session-id %)))
                             (sort-by :session-order))]
    (str/join "\n\n"
              (cons (format "# Session %s (%s %s)" id date time),
                    (for [e session-entries]
                      (:contents e))))))

(defn process-program [files]
  (let [program-entries (->> files (map render-program-entry))
        data (get-sessions)]
    (->> data
         :sessions
         (map #(render-program-session program-entries %))
         (str/join "\n\n"))))

(comment
  (process-program ))


(defn session->str [{:keys [date time order]}]
  (cond
    (and date time order)
    (str date " @ " time " @ " order)
    (and date time)
    (str date " @ " time)))

(defn get-by-id [id m]
  (->> m
       (filter #(= id (:id %)))
       first))

(defn render-latex [{:keys [sessions drive]} file]
  (let [header (read-yaml-header file)
        session-id (get-in header [:session :id])
        session (get-by-id session-id sessions)
        fullname (str (:name_first header) " " (:name_last header))
        drive-data (get drive fullname)
        output (tasks/shell {:out :string}
                            (format "pandoc -t latex --template=templates/conf-abstract.latex -V date=\"%s\" -V time=\"%s\" -V url=\"%s\" -V has_files=\"%s\" \"%s\""
                                    (:date session)
                                    (:time session)
                                    (:link drive-data)
                                    (boolean (:contains-files? drive-data))
                                    (.getAbsolutePath file)))
        index-name (:index_name header)]
    {:author (str (if index-name index-name (:name_last header)) "!" (:name_first header))
     :session session-id
     :order (get-in header [:session :order])
     :contents (:out output)}))

(defn process-latex [files]
  (let [data (merge (get-sessions) (get-drive))]
    (->> files
         (map #(render-latex data %))
         (sort-by :author)
         (sort-by :order)
         (sort-by :session)
         (map :contents)
         (str/join "\n\n"))))

(defn process-author-list [files]
  (->>
   (for [f files]
     (let [h (read-yaml-header f)]
       (str (:name_first h) " " (:name_last h))))
   (str/join "\n")))

(defn process-author-emails [files]
  (->>
   (for [f files]
     (let [h (read-yaml-header f)]
       {(str (:name_first h) " " (:name_last h))
        (str/split (:email h) #";\s+")}))
   (into {})
   pr-str))

(defn process-files [dir f]
  (let [files (file-seq (io/file dir))]
    (->> files
         (filter (or #(.endsWith (str %) ".md")
                     #(.endsWith (str %) ".txt")))
         (f))))

(def cli-spec
  {:spec
   {:format {:desc "Saída latex ou html"
             :default "html"
             :alias :f}
    :path {:desc "Diretório com fontes de dados .txt"
           :validate fs/directory?
           :require true}}})

(defn -main [args]
  (let [opts (cli/parse-opts args cli-spec)
        path (:path opts)]
    (print
     (case (:format opts)
       "html" (process-files path process-markdown)
       "latex" (process-files path process-latex)
       "program" (process-files path process-program)
       "authors" (process-files path process-author-list)
       "emails" (process-files path process-author-emails)))))

; (if-not (bound? #'*1))
(-main *command-line-args*)

(comment
  (cli/parse-opts ["--path" "data"] cli-spec)
  (-main ["--path" "abstracts" "-f" "latex"])

  (process-files "abstracts" process-program)
  (process-files "abstracts" process-latex)
  (-> (process-files "abstracts" process-author-emails)
      (get "Sarah Feldman"))

  (let [data (get-data "data/sessions.yml")]
    (render-latex data (io/file "abstracts/ABraga.md")))

  (let [data (get-data "data/sessions.yml")]
    (render-latex data (io/file "abstracts/LBelittaniRibeiro.md")))

  (let [data (get-data "data/sessions.yml")]
    (read-body (io/file "abstracts/ABraga.md")))

  (read-yaml-header "abstracts/BLConte.md")
  (read-body "abstracts/BLConte.md"))
