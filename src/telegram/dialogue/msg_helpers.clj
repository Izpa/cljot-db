(ns telegram.dialogue.msg-helpers
  (:require
   [clojure.string :as str]))

(defn user-id [msg]
  (or (get-in msg [:chat :id])
      (get-in msg [:callback_query :message :chat :id])))

(defn command-msg? [msg]
  (and (:text msg) (str/starts-with? (:text msg) "/")))

(defn circle? [msg]
  (or (contains? msg :video_note)
      (contains? (get msg :forward_from_message {}) :video_note)))

(defn classify-event [msg]
  (cond
    (command-msg? msg) :on-command
    (:text msg) :on-text
    (or (:video msg) (:video_note msg)) :on-file
    (:callback_query msg) :on-callback
    :else :unknown))

(defn extract-trigger [msg]
  (cond
    (:callback_query msg) (get-in msg [:callback_query :data])
    (command-msg? msg) (-> msg :text (str/split #"\s+") first (subs 1) keyword)
    :else :text))

(defn extract-file-id [msg]
  (or (get-in msg [:video :file_id])
      (get-in msg [:video_note :file_id])
      (get-in msg [:forward_from_message :video :file_id])
      (get-in msg [:forward_from_message :video_note :file_id])))

(def default-error-message "Команда не распознана или недоступна.")

