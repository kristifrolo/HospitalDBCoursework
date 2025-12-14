-- 1. Создание таблиц
CREATE TABLE hospitals (
    hospital_id serial PRIMARY KEY,
    name text NOT NULL,
    inn char(10) UNIQUE NOT NULL,
    address text,
    phone varchar(20)
);

CREATE TABLE departments (
    department_id serial PRIMARY KEY,
    hospital_id int NOT NULL REFERENCES hospitals(hospital_id) ON DELETE CASCADE,
    name text NOT NULL,
    head_doctor_id int, 
    UNIQUE (hospital_id, name)
);

CREATE TABLE positions (
    position_id serial PRIMARY KEY,
    title text NOT NULL UNIQUE
);

CREATE TABLE doctors (
    doctor_id serial PRIMARY KEY,
    hospital_id int NOT NULL REFERENCES hospitals(hospital_id) ON DELETE CASCADE,
    department_id int NOT NULL,
    inn varchar(12) UNIQUE NOT NULL,
    name text NOT NULL,
    surname TEXT NOT NULL,
    patronymic TEXT,
    position_id int NOT NULL REFERENCES positions(position_id)
);

ALTER TABLE doctors
    ADD CONSTRAINT fk_doctor_department FOREIGN KEY (department_id)
    REFERENCES departments(department_id) ON DELETE RESTRICT;

ALTER TABLE departments
    ADD CONSTRAINT fk_dept_head_doctor FOREIGN KEY (head_doctor_id)
    REFERENCES doctors(doctor_id) ON DELETE SET NULL;

CREATE TABLE diagnoses (
    diagnosis_id serial PRIMARY KEY,
    name text NOT NULL,
    treatment_notes text
);

CREATE TABLE patients (
    patient_id serial PRIMARY KEY,
    inn varchar(12),
    name text NOT NULL,
    surname TEXT NOT NULL,
    patronymic TEXT,
    birth_date date CHECK (birth_date <= CURRENT_DATE),
    gender char(1) CHECK (gender IN ('m','f')),
    phone varchar(20),
    address text
);

CREATE TABLE appointments (
    appointment_id serial PRIMARY KEY,
    patient_id int NOT NULL REFERENCES patients(patient_id) ON DELETE CASCADE,
    doctor_id int NOT NULL REFERENCES doctors(doctor_id) ON DELETE CASCADE,
    department_id int NOT NULL REFERENCES departments(department_id) ON DELETE CASCADE,
    hospital_id int NOT NULL REFERENCES hospitals(hospital_id) ON DELETE CASCADE,
    appointment_start timestamptz NOT NULL,
    appointment_end timestamptz NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'scheduled' 
        CHECK (status IN ('scheduled', 'completed', 'cancelled')),
    diagnosis_id int REFERENCES diagnoses(diagnosis_id),
    notes text
);

-- 2. Индексы 
CREATE INDEX idx_patients_surname ON patients(surname);
CREATE INDEX idx_patients_inn ON patients(inn);
CREATE INDEX idx_appointments_doctor_start ON appointments(doctor_id, appointment_start);
CREATE INDEX idx_appointments_patient_status ON appointments(patient_id, status);
CREATE INDEX idx_departments_hospital ON departments(hospital_id);
CREATE INDEX idx_doctors_hosp_dept ON doctors(hospital_id, department_id);
CREATE INDEX idx_appointments_start ON appointments(appointment_start);

-- 3. VIEW

-- По одной таблице: только совершеннолетние пациенты
CREATE VIEW v_patients_adults AS
SELECT 
    patient_id,
    surname,
    name,
    patronymic,
    birth_date,
    EXTRACT(YEAR FROM AGE(CURRENT_DATE, birth_date))::int AS age
FROM patients
WHERE birth_date <= CURRENT_DATE - INTERVAL '18 years';

-- По нескольким таблицам: полная картина приёма
CREATE VIEW v_appointments_full AS
SELECT
    a.appointment_id,
    h.name AS hospital,
    dep.name AS department,
    d.surname || ' ' || d.name || ' ' || COALESCE(d.patronymic || '.', '') AS doctor_full,
    p.surname || ' ' || p.name AS patient_name,
    a.appointment_start,
    a.appointment_end,
    a.status,
    diag.name AS diagnosis,
    a.notes
FROM appointments a
JOIN hospitals h USING (hospital_id)
JOIN departments dep USING (department_id)
JOIN doctors d USING (doctor_id)
JOIN patients p USING (patient_id)
LEFT JOIN diagnoses diag ON a.diagnosis_id = diag.diagnosis_id;

-- С GROUP BY и HAVING: отделения с ≥2 врачами
CREATE VIEW v_departments_with_staff AS
SELECT
    h.name AS hospital,
    dep.name AS department,
    COUNT(d.doctor_id) AS doctor_count
FROM departments dep
JOIN hospitals h USING (hospital_id)
LEFT JOIN doctors d ON dep.department_id = d.department_id
GROUP BY h.hospital_id, h.name, dep.department_id, dep.name
HAVING COUNT(d.doctor_id) >= 2
ORDER BY h.name, dep.name;

-- 4. ТРИГГЕР : 
-- При переводе приёма в статус 'cancelled' — автоматически дописать в notes дату/время отмены
CREATE OR REPLACE FUNCTION log_appointment_cancellation()
RETURNS TRIGGER AS $$
BEGIN
    -- Добавляем запись только при переходе в 'cancelled'
    IF NEW.status = 'cancelled' AND (OLD.status IS DISTINCT FROM 'cancelled') THEN
        NEW.notes := COALESCE(NEW.notes || '; ', '') 
                   || 'Отменено в ' || TO_CHAR(NOW(), 'YYYY-MM-DD HH24:MI');
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_log_cancellation
BEFORE UPDATE OF status ON appointments
FOR EACH ROW
WHEN (NEW.status = 'cancelled')
EXECUTE FUNCTION log_appointment_cancellation();

-- 5. Тестовые данные

-- Должности
INSERT INTO positions (title) VALUES 
    ('Терапевт'), ('Хирург'), ('Педиатр'), ('Кардиолог'), ('Невролог');

-- Больницы
INSERT INTO hospitals (name, inn, address, phone) VALUES 
    ('Городская клиническая больница №1', '1234567890', 'г. Ижевск, ул. Ленина, 10', '+73412123456'),
    ('Областная больница', '0987654321', 'г. Ижевск, пр. Победы, 45', '+73412987654');

-- Отделения
INSERT INTO departments (hospital_id, name) VALUES 
    (1, 'Терапевтическое'),
    (1, 'Хирургическое'),
    (1, 'Педиатрическое'),
    (2, 'Кардиологическое'),
    (2, 'Неврологическое');

-- Врачи
INSERT INTO doctors (hospital_id, department_id, inn, surname, name, patronymic, position_id) VALUES 
    (1, 1, '111111111111', 'Иванов', 'Алексей', 'Сергеевич', 1),
    (1, 2, '222222222222', 'Петров', 'Дмитрий', 'Андреевич', 2),
    (1, 3, '333333333333', 'Сидорова', 'Елена', 'Викторовна', 3),
    (2, 4, '444444444444', 'Козлов', 'Сергей', 'Иванович', 4),
    (2, 5, '555555555555', 'Морозова', 'Анна', 'Павловна', 5);

-- Назначаем заведующих
UPDATE departments SET head_doctor_id = 1 WHERE name = 'Терапевтическое';
UPDATE departments SET head_doctor_id = 2 WHERE name = 'Хирургическое';
UPDATE departments SET head_doctor_id = 4 WHERE name = 'Кардиологическое';

-- Диагнозы
INSERT INTO diagnoses (name, treatment_notes) VALUES 
    ('ОРВИ', 'Симптоматическое лечение, покой'),
    ('Артериальная гипертензия', 'Пожизненный приём антигипертензивных средств'),
    ('Перелом лучевой кости', 'Наложение гипса на 4–6 недель'),
    ('Бронхиальная астма', 'Хроническое заболевание, базисная и симптоматическая терапия');

-- Пациенты
INSERT INTO patients (inn, surname, name, patronymic, birth_date, gender, phone, address) VALUES 
    ('777777777777', 'Смирнов', 'Артём', 'Владимирович', '1985-03-12', 'm', '+79001112233', 'ул. Гагарина, д.5'),
    ('888888888888', 'Кузнецова', 'Мария', 'Олеговна', '1992-07-25', 'f', '+79004445566', 'пр. Мира, д.12'),
    ('999999999999', 'Волков', 'Иван', 'Степанович', '2010-11-30', 'm', '+79007778899', 'ул. Лесная, д.3'),
    ('101010101010', 'Лебедева', 'Ольга', 'Николаевна', '1978-01-18', 'f', '+79002223344', 'ул. Центральная, д.21');

-- Приёмы (текущая дата: 2025-12-01)
INSERT INTO appointments (patient_id, doctor_id, department_id, hospital_id, appointment_start, appointment_end, status, diagnosis_id, notes) VALUES 

    (1, 1, 1, 1, '2025-11-30 09:00:00+03', '2025-11-30 09:30:00+03', 'completed', 1, 'Жалобы на кашель. Назначено лечение.'),
    (2, 1, 1, 1, '2025-12-01 14:00:00+03', '2025-12-01 14:30:00+03', 'scheduled', NULL, 'Плановый осмотр'),
    (3, 2, 2, 1, '2025-12-02 11:00:00+03', '2025-12-02 11:45:00+03', 'scheduled', NULL, 'Травма правой руки'),
    (4, 4, 4, 2, '2025-12-08 10:00:00+03', '2025-12-08 10:30:00+03', 'scheduled', NULL, 'Контроль давления'),
    (1, 3, 3, 1, '2025-12-03 16:00:00+03', '2025-12-03 16:30:00+03', 'scheduled', NULL, 'Перенос по просьбе');
