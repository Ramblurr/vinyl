(ns ol.vinyl.interop.enum
  (:require
   [camel-snake-kebab.core :as csk]))

(defn enum->keyword
  "Convert an enum value to a namespaced keyword.
   Optionally provide a custom namespace, otherwise uses the enum's simple class name."
  ([enum-value]
   (enum->keyword enum-value nil))
  ([enum-value ns-override]
   (when enum-value
     (let [enum-class (.getDeclaringClass enum-value)
           ns-name (or ns-override
                       (-> (.getSimpleName enum-class)
                           csk/->kebab-case))] ; Remove leading dash
       (-> (.name enum-value)
           csk/->kebab-case
           (->> (keyword ns-name)))))))

(defn keyword->enum
  "Convert a namespaced keyword to an enum value.
   enum-class: The Class object for the enum type"
  [enum-class kw]
  (when (keyword? kw)
    (try
      (-> (name kw)
          csk/->SCREAMING_SNAKE_CASE
          (->> (Enum/valueOf enum-class)))
      (catch IllegalArgumentException _ nil))))

(defn create-enum-converters
  "Create a set of converter functions for a specific enum type.
   Returns a map with :enum->kw, :kw->enum, :int->kw, :kw->int functions."
  [enum-class & {:keys [namespace int-method]
                 :or {int-method "intValue"}}]
  (let [ns-name (or namespace
                    (-> (.getSimpleName enum-class)
                        csk/->kebab-case))

        get-int-value (fn [enum-val]
                        (try
                          (-> enum-class
                              (.getMethod int-method (into-array Class []))
                              (.invoke enum-val (object-array 0)))
                          (catch Exception _ nil)))

        enum-from-int (fn [n]
                        (try
                          (if-let [method (.getMethod enum-class "mediaType" (into-array Class [Integer/TYPE]))]
                            (.invoke method nil (object-array [n]))
                            ;; Fallback: search through values
                            (first (filter #(= n (get-int-value %))
                                           (.getEnumConstants enum-class))))
                          (catch Exception _ nil)))]

    {:enum->kw (fn [enum-val]
                 (enum->keyword enum-val ns-name))

     :kw->enum (fn [kw]
                 (keyword->enum enum-class kw))

     :int->kw (fn [n]
                (some-> (enum-from-int n)
                        (enum->keyword ns-name)))

     :kw->int (fn [kw]
                (some-> (keyword->enum enum-class kw)
                        get-int-value))

     :enum->kw-map (into {}
                         (map (fn [v] [v (enum->keyword v ns-name)]))
                         (.getEnumConstants enum-class))

     :kw->enum-map (into {}
                         (map (fn [v] [(enum->keyword v ns-name) v]))
                         (.getEnumConstants enum-class))}))

;; Usage examples:
;; (enum->keyword MediaType/DIRECTORY) => :media-type/directory
;; (enum->keyword MediaType/FILE_STREAM) => :media-type/file-stream
;; (keyword->enum MediaType :media-type/file-stream) => MediaType/FILE_STREAM

;; Create specialized converters:
;; (def media-converters (create-enum-converters MediaType))
;; ((:enum->kw media-converters) MediaType/DIRECTORY) => :media-type/directory
