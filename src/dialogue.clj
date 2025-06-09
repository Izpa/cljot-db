(ns dialogue
  (:require
   [integrant.core :as ig]
   [taoensso.timbre :as log]
   [telegrambot-lib.core :as tbot]
   [utils :refer [pformat]]
   [clojure.string :as str]))

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

(defmethod ig/init-key ::admin? [_ {:keys [admin-chat-ids]}]
  #(contains? admin-chat-ids %))

(defn command?
  [text]
  (when text (str/starts-with? text "/")))

(defmethod ig/init-key ::user-answer [_ _]
  (fn [msg answer]
    (answer msg)))

(defmethod ig/init-key ::admin-answer [_ _]
  (fn [msg answer]
    (answer msg)))

(defmethod ig/init-key ::check-invite [_ {:keys [invite]}]
  (fn [msg-text]
    (when invite
      (let [[command received-invite & args] (str/split msg-text #"\s+")]
        (and (= command "/start")
             (= invite received-invite)
             (empty? args))))))

(defmethod ig/init-key ::user [_ {:keys [admin? db-execute! check-invite]}]
  (fn [user-id text chat answer]
    (if-let [user (db-execute! {:select :*
                                :from :tg-user
                                :where [:= :id user-id]}
                               true)]
      user
      (when (or (admin? user-id)
                (check-invite text))
        (db-execute! {:insert-into :tg-user
                      :values [(select-keys chat
                                            [:id
                                             :username
                                             :last_name
                                             :first_name])]}
                     true)
        (answer "Добро пожаловать!")))))

(defmethod ig/init-key ::process-msg [_ {:keys [telegram-send
                                                admin?
                                                user-answer
                                                admin-answer
                                                user]}]
  (fn [{{:keys [id]
         :as chat} :chat
        :keys [text]
        :as msg}]
    (let [answer (partial telegram-send id)
          admin? (admin? id)
          user (user id text chat answer)]
      (if id
        (if user
          (if admin?
            (admin-answer msg answer)
            (user-answer msg answer))
          (answer "This is private bot. Access denied."))
        (log/warn "strange message without chat-id: " (pformat msg))))))
