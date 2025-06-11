(ns dialogue
  (:require
   [integrant.core :as ig]
   [taoensso.timbre :as log]
   [telegrambot-lib.core :as tbot]
   [utils :refer [pformat]]
   [clojure.string :as str])
  (:import (java.util UUID)))

;; --- FSM Helpers ---
(defn user-id [msg]
  (or (get-in msg [:chat :id])
      (get-in msg [:callback_query :message :chat :id])))

(defn command-msg? [msg]
  (and (:text msg) (str/starts-with? (:text msg) "/")))

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
    (command-msg? msg) (-> msg :text (subs 1) keyword)
    :else :text))

(defn extract-file-id [msg]
  (or (get-in msg [:video :file_id])
      (get-in msg [:video_note :file_id])
      (get-in msg [:forward_from_message :video :file_id])
      (get-in msg [:forward_from_message :video_note :file_id])))

(def default-error-message "Команда не распознана или недоступна.")

;; --- State Management ---
(defmethod ig/init-key ::user-states [_ _]
  (atom {}))

;; --- Telegram Messaging ---
(defn telegram-send
  ([bot to-id main-content] (telegram-send bot to-id main-content {}))
  ([bot to-id main-content additional-content]
   (let [sent_message (tbot/send-message bot
                                         to-id
                                         main-content
                                         (merge {:parse_mode "HTML"}
                                                additional-content))]
     (log/info "Send message: "
               (pformat sent_message))
     sent_message)))

(defmethod ig/init-key ::telegram-send [_ {:keys [bot]}]
  (partial telegram-send bot))

;; --- Role checking ---
(defmethod ig/init-key ::admin? [_ {:keys [admin-chat-ids]}]
  #(contains? admin-chat-ids %))

(defmethod ig/init-key ::check-user [_ {:keys [select-user check-invite insert-user!]}]
  (fn [user-id text chat answer]
    (cond
      (select-user user-id) :known
      (check-invite text) (do (insert-user! chat)
                              (answer "Добро пожаловать!")
                              :new)
      :else nil)))

(defmethod ig/init-key ::check-invite [_ {:keys [invite]}]
  (fn [msg-text]
    (when invite
      (let [[command received-invite & args] (str/split msg-text #"\\s+")]
        (and (= command "/start")
             (= invite received-invite)
             (empty? args))))))

(defmethod ig/init-key ::state-name->state [_ {:keys [user-states upload-file! insert-file! download-file]}]
  (fn [state-name]
    (get {:default
          {:on-command
           {:upload {:next :awaiting-video-name
                     :do (fn [{:keys [answer admin?]}]
                           (if admin?
                             (answer "Введите имя видео:")
                             (answer "Команда недоступна.")))}}
           :on-text
           {:do (fn [{:keys [answer msg]}]
                  (log/infof "Default state got on-text: %s" (:text msg))
                  (answer default-error-message))}}

          :awaiting-video-name
          {:on-text
           {:next :awaiting-video-file
            :do (fn [{:keys [answer user-id msg]}]
                  (log/infof "Processing video name input: %s" (:text msg))
                  (swap! user-states update user-id assoc
                         :state-name :awaiting-video-file
                         :state-data {:file-name (:text msg)})
                  (answer "Теперь отправьте само видео или кружок, можно пересланное."))}}

          :awaiting-video-file
          {:on-file
           {:next :default
            :do (fn [{:keys [answer user-id msg]}]
                  (let [file-id (extract-file-id msg)
                        file-name (get-in @user-states [user-id :state-data :file-name])]
                    (if file-id
                      (download-file file-id
                                     (fn [input-stream file-path]
                                       (let [ext (second (re-find #"\.([^.]+)$" file-path))
                                             storage-key (str (UUID/randomUUID)
                                                              (when ext (str "." ext)))]
                                         (upload-file! storage-key input-stream)
                                         (log/info "Saving to DB with: " {:name file-name :key storage-key})
                                         (insert-file! {:original_chat_id user-id
                                                        :original_message_id (:message_id msg)
                                                        :original_file_id file-id
                                                        :storage_key storage-key
                                                        :name file-name})
                                         (swap! user-states update-in [user-id :state-data] dissoc :file-name)
                                         (answer "Видео загружено."))))
                      (answer "Ожидалось видео или кружок."))))}}} state-name)))

;; --- FSM Dispatcher ---
(defmethod ig/init-key ::dispatch [_ {:keys [state-name->state user-states]}]
  (fn [msg answer {:keys [is-admin? is-new-user?]}]
    (let [uid        (user-id msg)
          {:keys [state-name]} (get @user-states uid {:state-name :default})
          state-def  (state-name->state state-name)
          event      (classify-event msg)
          trigger    (extract-trigger msg)
          transition (get-in state-def [event trigger]
                             (get-in state-def [event]))] ;; allow fallback on generic event
      (log/infof "FSM State=%s Event=%s Trigger=%s" state-name event trigger)
      (log/infof "FSM Transition: %s" (keys transition))
      (if transition
        (do
          ((:do transition) {:answer answer
                             :msg msg
                             :user-id uid
                             :admin? is-admin?
                             :new-user? is-new-user?})
          (log/infof "FSM: new state for user %s → %s" uid (:next transition))
          (swap! user-states update uid
                 (fn [state]
                   (-> state
                       (assoc :state-name (:next transition))))))
        (answer default-error-message)))))

(defmethod ig/init-key ::process-msg [_ {:keys [telegram-send dispatch admin? check-user]}]
  (fn [msg]
    (let [uid      (user-id msg)
          text     (:text msg)
          answer   (partial telegram-send uid)
          is-admin (admin? uid)
          result   (check-user uid text (:chat msg) answer)]
      (cond
        (nil? uid) (log/warn "Strange message without chat-id: " (pformat msg))
        (or is-admin result)
        (dispatch msg answer {:is-admin? is-admin
                              :is-new-user? (= result :new)})
        :else (answer "This is a private bot. Access denied.")))))
